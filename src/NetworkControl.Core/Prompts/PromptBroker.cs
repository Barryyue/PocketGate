using System.Runtime.CompilerServices;
using System.Threading.Channels;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Prompts;

/// <summary>
/// 詢問代理（設計文檔 §8）：
/// 同一用戶、同一應用的待決連接合併成一個彈窗；超時由服務計時，不依賴 UI；
/// 用戶答覆後生成規則（「僅本次」除外），並把決策應用到彈窗中被覆蓋的連接。
/// </summary>
public sealed partial class PromptBroker : IDisposable
{
    private readonly Lock _gate = new();
    private readonly Dictionary<Guid, PendingPrompt> _prompts = [];
    private readonly Dictionary<MergeKey, PendingPrompt> _byApp = [];
    private readonly List<Subscription> _subscriptions = [];
    private readonly RuleService _rules;
    private readonly RuntimeStateService _state;
    private readonly SettingsService _settings;
    private readonly CoreMetrics _metrics;
    private readonly TimeProvider _time;
    private readonly ILogger<PromptBroker> _logger;
    private int _pendingFlows;
    private bool _disposed;

    public PromptBroker(
        RuleService rules,
        RuntimeStateService state,
        SettingsService settings,
        CoreMetrics metrics,
        TimeProvider time,
        ILogger<PromptBroker> logger)
    {
        _rules = rules;
        _state = state;
        _settings = settings;
        _metrics = metrics;
        _time = time;
        _logger = logger;
    }

    /// <summary>引擎是否支持掛起連接；影響 UI 文案（FR-INT-07），由引擎宿主在啟動時設置。</summary>
    public bool EngineHoldsConnections { get; set; } = true;

    public int PendingFlowCount
    {
        get
        {
            lock (_gate)
            {
                return _pendingFlows;
            }
        }
    }

    /// <summary>是否有 UI 可以接收這個用戶的彈窗。</summary>
    public bool HasSubscriberFor(string? userId)
    {
        lock (_gate)
        {
            return _subscriptions.Any(s => Accepts(s.Subscriber, userId));
        }
    }

    public IReadOnlyList<PromptSnapshot> GetPending()
    {
        lock (_gate)
        {
            return _prompts.Values.Select(p => p.Snapshot()).ToArray();
        }
    }

    /// <summary>為一條連接請求用戶決策。返回的任務在用戶答覆或超時後完成。</summary>
    public Task<Verdict> RequestAsync(FlowInfo flow, AppIdentity? app, DomainCandidates domains, bool isTamperAlert)
    {
        var settings = _settings.Current;
        var pendingFlow = new PendingFlow(flow, domains);
        PromptEvent? evt;

        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);

            if (_pendingFlows >= settings.MaxPendingFlows)
            {
                LogTooManyPending(_logger, _pendingFlows);
                return Task.FromResult(new Verdict(settings.PromptDefaultAction, VerdictReason.FailSafe));
            }

            var key = new MergeKey(flow.UserId, app?.AppId, app is null ? flow.ExecutablePath : null, isTamperAlert);
            if (_byApp.TryGetValue(key, out var prompt))
            {
                prompt.Flows.Add(pendingFlow);
                evt = new PromptEvent(PromptEventKind.Updated, prompt.Snapshot());
            }
            else
            {
                var now = _time.GetUtcNow();
                prompt = new PendingPrompt(Guid.CreateVersion7(now), key, app, flow, isTamperAlert, EngineHoldsConnections, now, now + settings.PromptTimeout);
                prompt.Flows.Add(pendingFlow);
                prompt.Timer = _time.CreateTimer(static s => ((TimeoutCallback)s!).Fire(), new TimeoutCallback(this, prompt.Id), settings.PromptTimeout, Timeout.InfiniteTimeSpan);
                _prompts.Add(prompt.Id, prompt);
                _byApp.Add(key, prompt);
                evt = new PromptEvent(PromptEventKind.Added, prompt.Snapshot());
            }

            _pendingFlows++;
            _metrics.SetPendingPromptFlows(_pendingFlows);
        }

        Dispatch(evt);
        return pendingFlow.Completion.Task;
    }

    /// <summary>用戶回應彈窗。</summary>
    public async Task<PromptAnswerResult> AnswerAsync(Guid promptId, PromptDecision decision, CancellationToken cancellationToken)
    {
        PendingFlow head;
        AppIdentity? app;
        lock (_gate)
        {
            if (!_prompts.TryGetValue(promptId, out var prompt) || prompt.Flows.Count == 0)
            {
                return new PromptAnswerResult(false, null, 0, 0);
            }

            head = prompt.Flows[0];
            app = prompt.App;
        }

        // 規則生成和持久化在鎖外進行
        var rule = PromptRuleFactory.CreateRule(
            decision, head.Flow, app, head.Domains, _state.ActiveProfileId, _time.GetUtcNow(), _settings.Current.OnceFallbackDuration);
        if (rule is not null)
        {
            rule = await _rules.AddAsync(rule, cancellationToken);
        }

        var coverage = new CompiledRule(new Rule
        {
            Id = Guid.Empty,
            Conditions = PromptRuleFactory.CreateConditions(decision, head.Flow, head.Domains),
            Action = decision.Action,
            Lifetime = RuleLifetime.Once,
            Source = RuleSource.Prompt,
            CreatedAt = default,
            UpdatedAt = default,
        });

        var verdict = new Verdict(decision.Action, VerdictReason.Prompt, rule?.Id);
        List<PendingFlow> resolved;
        PromptEvent evt;
        int remaining;

        lock (_gate)
        {
            if (!_prompts.TryGetValue(promptId, out var prompt))
            {
                // 在生成規則期間已超時：規則仍然保留，只是沒有待決連接可以應用
                return new PromptAnswerResult(true, rule?.Id, 0, 0);
            }

            resolved = decision.ApplyToAllFlows
                ? prompt.Flows.Where(f => f == head || coverage.MatchConditions(f.ToEvaluationContext(app))).ToList()
                : [head];

            foreach (var f in resolved)
            {
                prompt.Flows.Remove(f);
            }

            _pendingFlows -= resolved.Count;
            _metrics.SetPendingPromptFlows(_pendingFlows);
            remaining = prompt.Flows.Count;

            if (remaining == 0)
            {
                Remove(prompt);
                evt = new PromptEvent(PromptEventKind.Removed, prompt.Snapshot());
            }
            else
            {
                evt = new PromptEvent(PromptEventKind.Updated, prompt.Snapshot());
            }
        }

        foreach (var f in resolved)
        {
            f.Completion.TrySetResult(verdict);
        }

        Dispatch(evt);
        LogAnswered(_logger, promptId, decision.Action, decision.Lifetime, resolved.Count);
        return new PromptAnswerResult(true, rule?.Id, resolved.Count, remaining);
    }

    /// <summary>暫停倒計時：把截止時間推遲一個超時週期，但不超過彈窗的最長存活時間。</summary>
    public bool PauseTimer(Guid promptId)
    {
        PromptEvent evt;
        lock (_gate)
        {
            if (!_prompts.TryGetValue(promptId, out var prompt))
            {
                return false;
            }

            var settings = _settings.Current;
            var now = _time.GetUtcNow();
            var limit = prompt.CreatedAt + settings.MaxPromptLifetime;
            var deadline = now + settings.PromptTimeout < limit ? now + settings.PromptTimeout : limit;
            if (deadline <= prompt.Deadline)
            {
                return false;
            }

            prompt.Deadline = deadline;
            prompt.Timer?.Change(deadline - now, Timeout.InfiniteTimeSpan);
            evt = new PromptEvent(PromptEventKind.Updated, prompt.Snapshot());
        }

        Dispatch(evt);
        return true;
    }

    /// <summary>訂閱彈窗事件。訂閱時先收到所有已存在的彈窗（Added），之後是增量事件。</summary>
    public async IAsyncEnumerable<PromptEvent> SubscribeAsync(PromptSubscriber subscriber, [EnumeratorCancellation] CancellationToken cancellationToken)
    {
        var channel = Channel.CreateUnbounded<PromptEvent>(new UnboundedChannelOptions { SingleReader = true });
        var subscription = new Subscription(subscriber, channel);

        lock (_gate)
        {
            _subscriptions.Add(subscription);
            foreach (var prompt in _prompts.Values.Where(p => ShouldDeliver(subscriber, p.TargetUserId)))
            {
                channel.Writer.TryWrite(new PromptEvent(PromptEventKind.Added, prompt.Snapshot()));
            }
        }

        try
        {
            await foreach (var evt in channel.Reader.ReadAllAsync(cancellationToken))
            {
                yield return evt;
            }
        }
        finally
        {
            lock (_gate)
            {
                _subscriptions.Remove(subscription);
            }
        }
    }

    /// <summary>服務停止時調用：所有待決連接按失效策略完成。</summary>
    public void CancelAll()
    {
        List<PendingPrompt> prompts;
        lock (_gate)
        {
            prompts = [.. _prompts.Values];
            foreach (var p in prompts)
            {
                Remove(p);
            }

            _pendingFlows = 0;
            _metrics.SetPendingPromptFlows(0);
        }

        var action = _settings.Current.FailPolicy == FailPolicy.FailClosed ? VerdictAction.Deny : VerdictAction.Allow;
        foreach (var flow in prompts.SelectMany(p => p.Flows))
        {
            flow.Completion.TrySetResult(new Verdict(action, VerdictReason.Cancelled));
        }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed)
            {
                return;
            }

            _disposed = true;
        }

        CancelAll();
    }

    private void OnTimeout(Guid promptId)
    {
        PendingPrompt prompt;
        lock (_gate)
        {
            if (!_prompts.TryGetValue(promptId, out prompt!))
            {
                return;
            }

            Remove(prompt);
            _pendingFlows -= prompt.Flows.Count;
            _metrics.SetPendingPromptFlows(_pendingFlows);
        }

        var verdict = new Verdict(_settings.Current.PromptDefaultAction, VerdictReason.PromptTimeout);
        foreach (var flow in prompt.Flows)
        {
            flow.Completion.TrySetResult(verdict);
        }

        Dispatch(new PromptEvent(PromptEventKind.Removed, prompt.Snapshot()));
        LogTimedOut(_logger, promptId, prompt.Flows.Count);
    }

    /// <summary>必須在持有鎖時調用。</summary>
    private void Remove(PendingPrompt prompt)
    {
        _prompts.Remove(prompt.Id);
        _byApp.Remove(prompt.Key);
        prompt.Timer?.Dispose();
        prompt.Timer = null;
    }

    private void Dispatch(PromptEvent evt)
    {
        lock (_gate)
        {
            foreach (var s in _subscriptions)
            {
                if (ShouldDeliver(s.Subscriber, evt.Prompt.TargetUserId))
                {
                    s.Channel.Writer.TryWrite(evt);
                }
            }
        }
    }

    /// <summary>
    /// 路由規則（FR-PRM-13）：發給發起連接的用戶本人；該用戶沒有在線的 UI 時，發給管理員。必須在持有鎖時調用。
    /// </summary>
    private bool ShouldDeliver(PromptSubscriber subscriber, string? targetUserId)
    {
        if (targetUserId is null || Owns(subscriber, targetUserId))
        {
            return true;
        }

        return subscriber.IsAdmin && !_subscriptions.Any(s => Owns(s.Subscriber, targetUserId));
    }

    private bool Accepts(PromptSubscriber subscriber, string? targetUserId) => ShouldDeliver(subscriber, targetUserId);

    private static bool Owns(PromptSubscriber subscriber, string targetUserId) =>
        string.Equals(subscriber.UserId, targetUserId, StringComparison.OrdinalIgnoreCase);

    [LoggerMessage(Level = LogLevel.Warning, Message = "待決連接數已達上限 {Count}，新連接按默認動作處理")]
    private static partial void LogTooManyPending(ILogger logger, int count);

    [LoggerMessage(Level = LogLevel.Information, Message = "彈窗 {PromptId} 已回應：{Action} / {Lifetime}，處理了 {Count} 條連接")]
    private static partial void LogAnswered(ILogger logger, Guid promptId, VerdictAction action, RuleLifetime lifetime, int count);

    [LoggerMessage(Level = LogLevel.Information, Message = "彈窗 {PromptId} 超時，{Count} 條連接按默認動作處理")]
    private static partial void LogTimedOut(ILogger logger, Guid promptId, int count);

    private readonly record struct MergeKey(string? UserId, long? AppId, string? UnknownAppPath, bool IsTamperAlert);

    private sealed record Subscription(PromptSubscriber Subscriber, Channel<PromptEvent> Channel);

    private sealed class TimeoutCallback(PromptBroker owner, Guid promptId)
    {
        public void Fire() => owner.OnTimeout(promptId);
    }

    private sealed class PendingFlow(FlowInfo flow, DomainCandidates domains)
    {
        public FlowInfo Flow { get; } = flow;

        public DomainCandidates Domains { get; } = domains;

        public TaskCompletionSource<Verdict> Completion { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public EvaluationContext ToEvaluationContext(AppIdentity? app) =>
            new(Flow, app, Domains, NetworkZones.Classify(Flow.Remote.Address));

        public PromptFlowSnapshot Snapshot() =>
            new(Flow.FlowId, Flow.Direction, Flow.Protocol, Flow.Local, Flow.Remote, Domains.Names, Flow.Timestamp);
    }

    private sealed class PendingPrompt(
        Guid id,
        MergeKey key,
        AppIdentity? app,
        FlowInfo firstFlow,
        bool isTamperAlert,
        bool isConnectionHeld,
        DateTimeOffset createdAt,
        DateTimeOffset deadline)
    {
        public Guid Id { get; } = id;

        public MergeKey Key { get; } = key;

        public AppIdentity? App { get; } = app;

        public string? TargetUserId => Key.UserId;

        public List<PendingFlow> Flows { get; } = [];

        public DateTimeOffset CreatedAt { get; } = createdAt;

        public DateTimeOffset Deadline { get; set; } = deadline;

        public ITimer? Timer { get; set; }

        public PromptSnapshot Snapshot() => new(
            Id,
            App,
            App?.Path ?? firstFlow.ExecutablePath,
            firstFlow.Process,
            TargetUserId,
            Flows.Select(f => f.Snapshot()).ToArray(),
            CreatedAt,
            Deadline,
            isTamperAlert,
            isConnectionHeld && firstFlow.Origin == FlowOrigin.Pending);
    }
}
