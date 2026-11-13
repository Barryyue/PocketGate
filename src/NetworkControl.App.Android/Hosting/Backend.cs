using System.Runtime.CompilerServices;
using global::Android.Content;
using NetworkControl.Android.Platform;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Engine.Android;
using NetworkControl.Engine.Vpn;
using NetworkControl.Engine.Vpn.Platform;
using NetworkControl.Engine.Vpn.Routing;

namespace NetworkControl.Android.Hosting;

/// <summary>保護狀態（界面顯示用）。</summary>
public enum ProtectionStatus : byte
{
    Off = 0,
    NeedsConsent = 1,
    Starting = 2,
    On = 3,
    Revoked = 4,
    Failed = 5,
}

/// <summary>用戶在應用列表中為一個應用設定的決策。</summary>
public enum AppDecision : byte
{
    /// <summary>沒有應用專屬規則：按全局模式（或全局規則）處理。</summary>
    Unset = 0,
    Allow = 1,
    Deny = 2,
}

/// <summary>應用列表中的一行。</summary>
/// <param name="App">應用。</param>
/// <param name="Decision">應用專屬規則的決策。</param>
/// <param name="Effective">當前實際的分流結果。</param>
/// <param name="Reason">分流依據。</param>
/// <param name="ExpiresAt">應用專屬規則是限時規則時的到期時間。</param>
/// <param name="IsTemporary">應用專屬規則不是永久規則（限時、直到重啟）。</param>
/// <param name="IsTampered">有規則因簽名不一致被跳過。</param>
public sealed record AppEntry(
    InstalledApp App,
    AppDecision Decision,
    AppRoutingDecision Effective,
    AppRoutingReason Reason,
    DateTimeOffset? ExpiresAt,
    bool IsTemporary,
    bool IsTampered);

/// <summary>
/// 界面訪問核心的門面（docs/android.md §7.1）。所有方法都可以在任意線程調用；<see cref="Changed"/> 可能在後台線程觸發。
/// </summary>
public interface IBackend
{
    event EventHandler? Changed;

    ProtectionStatus Protection { get; }

    string? ProtectionError { get; }

    FilterMode Mode { get; }

    int PendingPromptCount { get; }

    /// <summary>在 Android 上不生效的規則數（帶域名、IP、端口等條件）。</summary>
    int SkippedRuleCount { get; }

    UserSettings Settings { get; }

    VpnEngineCounters Counters { get; }

    /// <summary>用戶授權 VPN 的系統對話框；已授權時返回 null。</summary>
    Intent? CreateVpnConsentIntent();

    void SetProtection(bool enabled);

    Task SetModeAsync(FilterMode mode, CancellationToken cancellationToken);

    Task SetBlockAllAsync(bool blockAll, CancellationToken cancellationToken);

    IReadOnlyList<AppEntry> GetApps();

    Task SetAppDecisionAsync(string packageName, AppDecision decision, CancellationToken cancellationToken);

    Task<IReadOnlyList<LogEntry>> GetBlockedAsync(int limit, CancellationToken cancellationToken);

    Task<long> CountBlockedSinceAsync(DateTimeOffset since, CancellationToken cancellationToken);

    IReadOnlyList<PromptSnapshot> GetPendingPrompts();

    IAsyncEnumerable<PromptEvent> SubscribePromptsAsync(CancellationToken cancellationToken);

    Task AnswerPromptAsync(Guid promptId, VerdictAction action, RuleLifetime lifetime, TimeSpan? duration, CancellationToken cancellationToken);

    Task UpdateSettingsAsync(Func<UserSettings, UserSettings> update, CancellationToken cancellationToken);
}

internal sealed class Backend : IBackend
{
    private readonly VpnBlackholeEngine _engine;
    private readonly AndroidVpnTunnel _tunnel;
    private readonly IProtectionSwitch _protection;
    private readonly IInstalledAppCatalog _catalog;
    private readonly RuntimeStateService _state;
    private readonly SettingsService _settings;
    private readonly RuleEngine _rules;
    private readonly RuleService _ruleService;
    private readonly PromptBroker _prompts;
    private readonly IActivityQuery _activity;
    private readonly TimeProvider _time;
    private readonly string _selfPackage;

    public Backend(
        Context context,
        VpnBlackholeEngine engine,
        AndroidVpnTunnel tunnel,
        IProtectionSwitch protection,
        IInstalledAppCatalog catalog,
        RuntimeStateService state,
        SettingsService settings,
        RuleEngine rules,
        RuleService ruleService,
        PromptBroker prompts,
        PromptNotifier notifier,
        IActivityQuery activity,
        TimeProvider time)
    {
        _engine = engine;
        _tunnel = tunnel;
        _protection = protection;
        _catalog = catalog;
        _state = state;
        _settings = settings;
        _rules = rules;
        _ruleService = ruleService;
        _prompts = prompts;
        _activity = activity;
        _time = time;
        _selfPackage = context.PackageName ?? "";

        _engine.StatusChanged += Raise;
        _protection.Changed += Raise;
        _state.Changed += Raise;
        _settings.Changed += Raise;
        notifier.PromptsChanged += Raise;
    }

    public event EventHandler? Changed;

    public ProtectionStatus Protection
    {
        get
        {
            var tunnel = _engine.TunnelState;
            if (!_protection.IsEnabled)
            {
                return tunnel == VpnTunnelState.Revoked ? ProtectionStatus.Revoked : ProtectionStatus.Off;
            }

            return tunnel switch
            {
                VpnTunnelState.Active => ProtectionStatus.On,
                VpnTunnelState.Failed => ProtectionStatus.Failed,
                VpnTunnelState.NotPrepared => ProtectionStatus.NeedsConsent,
                _ when !_tunnel.IsPrepared => ProtectionStatus.NeedsConsent,
                _ => ProtectionStatus.Starting,
            };
        }
    }

    public string? ProtectionError => _engine.TunnelError;

    public FilterMode Mode => _state.Mode;

    public int PendingPromptCount => _prompts.GetPending().Count;

    public int SkippedRuleCount => _engine.CurrentPlan.SkippedRules.Count;

    public UserSettings Settings => _settings.Current;

    public VpnEngineCounters Counters => _engine.Counters;

    public Intent? CreateVpnConsentIntent() => _tunnel.CreatePrepareIntent();

    public void SetProtection(bool enabled)
    {
        _protection.SetEnabled(enabled);
        // 授權剛完成時開關可能沒有變化（之前已打開），仍然需要重新規劃
        _engine.RequestReplan();
    }

    public Task SetModeAsync(FilterMode mode, CancellationToken cancellationToken) => _state.SetModeAsync(mode, cancellationToken);

    public Task SetBlockAllAsync(bool blockAll, CancellationToken cancellationToken) => blockAll
        ? _state.SetModeAsync(FilterMode.BlockAll, cancellationToken)
        : _state.RestoreFromBlockAllAsync(cancellationToken);

    public IReadOnlyList<AppEntry> GetApps()
    {
        var plan = _engine.CurrentPlan;
        var rules = _rules.Current.Rules;
        var result = new List<AppEntry>();
        foreach (var app in _catalog.GetApps())
        {
            if (app.PackageName == _selfPackage)
            {
                continue;
            }

            var own = FindOwnRule(rules, app.PackageName);
            var routing = plan.Find(app.Uid);
            result.Add(new AppEntry(
                app,
                own is null ? AppDecision.Unset : own.Action == VerdictAction.Allow ? AppDecision.Allow : AppDecision.Deny,
                routing?.Decision ?? AppRoutingDecision.BlockAndAsk,
                routing?.Reason ?? AppRoutingReason.Mode,
                own?.Lifetime == RuleLifetime.Timed ? own.ExpiresAt : null,
                own is { IsPersistent: false } or { Lifetime: RuleLifetime.Timed },
                routing?.TamperedRuleId is not null));
        }

        return result;
    }

    public async Task SetAppDecisionAsync(string packageName, AppDecision decision, CancellationToken cancellationToken)
    {
        var path = PackageAppPath.ForPackage(packageName);
        var existing = _ruleService.All
            .Where(r => r.Source is RuleSource.User or RuleSource.Prompt && IsOwnRule(r, path))
            .Select(r => r.Id)
            .ToArray();
        if (existing.Length > 0)
        {
            await _ruleService.RemoveAsync(existing, RuleEndReason.Replaced, cancellationToken);
        }

        if (decision == AppDecision.Unset || _catalog.Find(packageName) is not { } app)
        {
            return;
        }

        var now = _time.GetUtcNow();
        await _ruleService.AddAsync(new Rule
        {
            Id = Guid.CreateVersion7(now),
            App = AppMatcherFactory.Create(VpnRoutingPlanner.ToIdentity(app)),
            Conditions = new RuleConditions { Direction = Direction.Outbound },
            Action = decision == AppDecision.Allow ? VerdictAction.Allow : VerdictAction.Deny,
            Lifetime = RuleLifetime.Permanent,
            Source = RuleSource.User,
            CreatedAt = now,
            UpdatedAt = now,
        }, cancellationToken);
    }

    public Task<IReadOnlyList<LogEntry>> GetBlockedAsync(int limit, CancellationToken cancellationToken) =>
        _activity.QueryAsync(new LogQuery { Action = VerdictAction.Deny, Limit = limit }, cancellationToken);

    public async Task<long> CountBlockedSinceAsync(DateTimeOffset since, CancellationToken cancellationToken)
    {
        var entries = await _activity.QueryAsync(new LogQuery { Action = VerdictAction.Deny, From = since, Limit = 5000 }, cancellationToken);
        return entries.Sum(e => (long)e.Count);
    }

    public IReadOnlyList<PromptSnapshot> GetPendingPrompts() => _prompts.GetPending();

    public async IAsyncEnumerable<PromptEvent> SubscribePromptsAsync([EnumeratorCancellation] CancellationToken cancellationToken)
    {
        await foreach (var evt in _prompts.SubscribeAsync(new PromptSubscriber(UserId: null, IsAdmin: true), cancellationToken))
        {
            yield return evt;
        }
    }

    public Task AnswerPromptAsync(Guid promptId, VerdictAction action, RuleLifetime lifetime, TimeSpan? duration, CancellationToken cancellationToken) =>
        _prompts.AnswerAsync(promptId, new PromptDecision
        {
            Action = action,
            Lifetime = lifetime,
            Duration = duration,
            Scope = PromptScope.AnyConnection,
        }, cancellationToken);

    public Task UpdateSettingsAsync(Func<UserSettings, UserSettings> update, CancellationToken cancellationToken) =>
        _settings.UpdateAsync(update, cancellationToken);

    /// <summary>按優先級找到該應用的第一條專屬、可在 Android 上執行的規則。</summary>
    private static Rule? FindOwnRule(IReadOnlyList<Rule> rules, string packageName)
    {
        var path = PackageAppPath.ForPackage(packageName);
        return rules.FirstOrDefault(r => IsOwnRule(r, path));
    }

    private static bool IsOwnRule(Rule rule, string path) =>
        rule.App is { HasWildcard: false } matcher
        && string.Equals(matcher.PathPattern, path, StringComparison.Ordinal)
        && VpnRoutingPlanner.IsEnforceable(rule);

    private void Raise(object? sender, EventArgs e) => Changed?.Invoke(this, EventArgs.Empty);
}
