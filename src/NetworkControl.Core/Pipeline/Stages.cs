using System.Collections.Concurrent;
using System.Net;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Pipeline;

/// <summary>停用過濾模式：全部允許。</summary>
internal sealed class DisabledModeStage(RuntimeStateService state) : IVerdictStage
{
    public int Order => StageOrder.DisabledMode;

    public ValueTask<Verdict?> EvaluateAsync(FlowContext context) =>
        ValueTask.FromResult<Verdict?>(state.Mode == FilterMode.Disabled ? Verdict.Allow(VerdictReason.Mode) : null);
}

/// <summary>
/// 內置必要流量（FR-INT-03 / 04 / 05）：回環、自身進程、DHCP、ICMPv6 鄰居發現。
/// 在「全部阻斷」之前執行，保證系統基本網絡功能和自身不被誤殺。
/// </summary>
internal sealed class BuiltinTrafficStage(SettingsService settings) : IVerdictStage
{
    private static readonly int SelfPid = Environment.ProcessId;

    public int Order => StageOrder.Builtin;

    public ValueTask<Verdict?> EvaluateAsync(FlowContext context)
    {
        var flow = context.Flow;
        var allow = context.RemoteZone == NetworkZone.Loopback
            || flow.Process?.Pid == SelfPid
            || IsDhcp(flow)
            || flow.Protocol == L4Protocol.IcmpV6
            || (flow.Protocol == L4Protocol.Icmp && settings.Current.AllowIcmpByDefault);

        return ValueTask.FromResult<Verdict?>(allow ? Verdict.Allow(VerdictReason.Builtin) : null);
    }

    private static bool IsDhcp(FlowInfo flow) =>
        flow.Protocol == L4Protocol.Udp && flow.Remote.Port is 67 or 68 or 546 or 547;
}

/// <summary>全部阻斷模式。</summary>
internal sealed class BlockAllStage(RuntimeStateService state) : IVerdictStage
{
    public int Order => StageOrder.BlockAll;

    public ValueTask<Verdict?> EvaluateAsync(FlowContext context) =>
        ValueTask.FromResult<Verdict?>(state.Mode == FilterMode.BlockAll ? Verdict.Deny(VerdictReason.Mode) : null);
}

/// <summary>規則匹配階段，帶判決緩存（設計文檔 §5.5）。</summary>
internal sealed class RuleStage(RuleEngine engine, RuleService rules) : IVerdictStage
{
    private const int MaxCacheEntries = 65_536;

    private readonly ConcurrentDictionary<CacheKey, RuleMatch> _cache = new();
    private long _cacheVersion = -1;

    public int Order => StageOrder.Rules;

    public async ValueTask<Verdict?> EvaluateAsync(FlowContext context)
    {
        var app = await context.GetAppAsync();
        var ruleSet = engine.Current;
        var flow = context.Flow;

        // 規則集版本變化時整體清空緩存（惰性失效）
        if (Interlocked.Exchange(ref _cacheVersion, ruleSet.Version) != ruleSet.Version || _cache.Count >= MaxCacheEntries)
        {
            _cache.Clear();
        }

        var domains = context.Domains;
        // 緩存鍵包含完整的應用身份（簽名者、哈希、命令行）：程序被替換時身份變化，舊判決不會被複用
        var key = new CacheKey(
            app,
            flow.Direction,
            flow.Protocol,
            flow.Remote.Address,
            flow.Remote.Port,
            flow.Local.Port,
            domains.IsEmpty ? null : string.Join('|', domains.Names));

        if (!_cache.TryGetValue(key, out var match))
        {
            match = ruleSet.Evaluate(context.ToEvaluationContext());
            _cache[key] = match;
        }

        context.Match = match;
        if (match.Rule is not { } rule)
        {
            return null;
        }

        rules.RecordHit(rule.Id);
        return new Verdict(rule.Action, VerdictReason.Rule, rule.Id);
    }

    private readonly record struct CacheKey(
        AppIdentity? App,
        Direction Direction,
        L4Protocol Protocol,
        IPAddress RemoteAddress,
        int RemotePort,
        int LocalPort,
        string? Domains);
}

/// <summary>
/// 兜底階段：沒有規則命中時按全局模式處理。詢問模式下交給 <see cref="PromptBroker"/>。
/// </summary>
internal sealed class FallbackStage(RuntimeStateService state, SettingsService settings, PromptBroker prompts) : IVerdictStage
{
    public int Order => StageOrder.Fallback;

    public async ValueTask<Verdict?> EvaluateAsync(FlowContext context)
    {
        var tampered = context.Match?.TamperedRule is not null;
        switch (state.Mode)
        {
            case FilterMode.Blacklist when !tampered:
                return Verdict.Allow(VerdictReason.Mode);
            case FilterMode.Whitelist when !tampered:
                return Verdict.Deny(VerdictReason.Mode);
        }

        var defaultAction = settings.Current.PromptDefaultAction;
        if (state.IsDoNotDisturb)
        {
            return new Verdict(defaultAction, VerdictReason.DoNotDisturb);
        }

        if (!prompts.HasSubscriberFor(context.Flow.UserId))
        {
            return new Verdict(defaultAction, VerdictReason.NoUserSession);
        }

        var app = await context.GetAppAsync();
        var pending = prompts.RequestAsync(context.Flow, app, context.Domains, tampered);

        if (context.Flow.Origin == FlowOrigin.BlockedEvent)
        {
            // 連接已被引擎阻斷，不等待用戶；用戶允許後會生成規則，應用重試即可連上
            _ = pending;
            return Verdict.Deny(VerdictReason.Mode);
        }

        return await pending.WaitAsync(context.CancellationToken);
    }
}
