using System.Collections.Concurrent;
using System.Collections.Immutable;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Common;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Rules;

/// <summary>持久化的規則及其命中統計。</summary>
public sealed record StoredRule(Rule Rule, long HitCount = 0, DateTimeOffset? LastHitAt = null);

/// <summary>一條規則在一段時間內的命中增量。</summary>
public readonly record struct RuleHitDelta(Guid RuleId, long Count, DateTimeOffset LastHitAt);

/// <summary>【存儲實現】規則持久化。</summary>
public interface IRuleRepository
{
    Task<IReadOnlyList<StoredRule>> LoadAllAsync(CancellationToken cancellationToken);

    Task UpsertAsync(Rule rule, CancellationToken cancellationToken);

    /// <summary>刪除規則並寫入歷史表，用於審計。</summary>
    Task DeleteAsync(IReadOnlyCollection<Rule> rules, RuleEndReason reason, DateTimeOffset endedAt, CancellationToken cancellationToken);

    Task RecordHitsAsync(IReadOnlyCollection<RuleHitDelta> hits, CancellationToken cancellationToken);
}

/// <summary>【擴展點】提供內置規則（如 DHCP、系統 DNS 服務），每次啟動時重新生成，不寫入存儲。</summary>
public interface IBuiltinRuleProvider
{
    IEnumerable<Rule> GetRules(DateTimeOffset now);
}

public enum RuleChangeKind : byte
{
    Added = 0,
    Updated = 1,
    Removed = 2,
}

/// <summary>規則變更事件，推送給 UI。</summary>
public sealed record RuleChange(RuleChangeKind Kind, Rule Rule, RuleEndReason? EndReason = null);

/// <summary>
/// 規則的應用服務：維護全部規則（含停用、臨時規則），變更時持久化並重新發布生效規則集。
/// 所有寫操作串行化；讀操作讀取不可變快照，無鎖。
/// </summary>
public sealed partial class RuleService(
    IRuleRepository repository,
    RuleEngine engine,
    RuntimeStateService state,
    IEnumerable<IBuiltinRuleProvider> builtinProviders,
    TimeProvider time,
    ILogger<RuleService> logger)
{
    private readonly SemaphoreSlim _writeGate = new(1, 1);
    private readonly ConcurrentDictionary<Guid, HitCounter> _hits = new();
    private ImmutableDictionary<Guid, Rule> _rules = ImmutableDictionary<Guid, Rule>.Empty;

    /// <summary>規則變更（增、改、刪）。</summary>
    public Broadcaster<RuleChange> Changes { get; } = new();

    /// <summary>規則被添加後觸發（同步回調，用於生命週期管理註冊進程監聽）。</summary>
    public event EventHandler<RuleChange>? Changed;

    public IReadOnlyCollection<Rule> All => Volatile.Read(ref _rules).Values.ToArray();

    public Rule? Find(Guid id) => Volatile.Read(ref _rules).GetValueOrDefault(id);

    public async Task InitializeAsync(CancellationToken cancellationToken)
    {
        var now = time.GetUtcNow();
        var stored = await repository.LoadAllAsync(cancellationToken);
        var builder = ImmutableDictionary.CreateBuilder<Guid, Rule>();

        foreach (var s in stored)
        {
            builder[s.Rule.Id] = s.Rule;
            _hits[s.Rule.Id] = new HitCounter(s.HitCount, s.LastHitAt);
        }

        foreach (var rule in builtinProviders.SelectMany(p => p.GetRules(now)))
        {
            builder[rule.Id] = rule;
        }

        Volatile.Write(ref _rules, builder.ToImmutable());
        state.Changed += (_, _) => Republish();
        Republish();
        LogLoaded(logger, stored.Count, builder.Count - stored.Count);
    }

    public async Task<Rule> AddAsync(Rule rule, CancellationToken cancellationToken)
    {
        Validate(rule);
        await _writeGate.WaitAsync(cancellationToken);
        try
        {
            if (rule.IsPersistent)
            {
                await repository.UpsertAsync(rule, cancellationToken);
            }

            ImmutableInterlocked.Update(ref _rules, (d, r) => d.SetItem(r.Id, r), rule);
            _hits.TryAdd(rule.Id, new HitCounter(0, null));
            Republish();
        }
        finally
        {
            _writeGate.Release();
        }

        Notify(new RuleChange(RuleChangeKind.Added, rule));
        return rule;
    }

    public async Task<Rule> UpdateAsync(Rule rule, CancellationToken cancellationToken)
    {
        Validate(rule);
        Rule updated;
        await _writeGate.WaitAsync(cancellationToken);
        try
        {
            var existing = Find(rule.Id) ?? throw new KeyNotFoundException($"規則 {rule.Id} 不存在。");
            if (existing.Source == RuleSource.Builtin)
            {
                // 內置規則只允許啟用 / 停用
                rule = existing with { Enabled = rule.Enabled };
            }

            updated = rule with { CreatedAt = existing.CreatedAt, UpdatedAt = time.GetUtcNow() };
            if (updated.IsPersistent)
            {
                await repository.UpsertAsync(updated, cancellationToken);
            }

            ImmutableInterlocked.Update(ref _rules, (d, r) => d.SetItem(r.Id, r), updated);
            Republish();
        }
        finally
        {
            _writeGate.Release();
        }

        Notify(new RuleChange(RuleChangeKind.Updated, updated));
        return updated;
    }

    public async Task<Rule> SetEnabledAsync(Guid id, bool enabled, CancellationToken cancellationToken)
    {
        var existing = Find(id) ?? throw new KeyNotFoundException($"規則 {id} 不存在。");
        return existing.Enabled == enabled ? existing : await UpdateAsync(existing with { Enabled = enabled }, cancellationToken);
    }

    /// <summary>刪除規則；內置規則不能刪除（只能停用），會被忽略。返回實際刪除的數量。</summary>
    public async Task<int> RemoveAsync(IReadOnlyCollection<Guid> ids, RuleEndReason reason, CancellationToken cancellationToken)
    {
        List<Rule> removed;
        await _writeGate.WaitAsync(cancellationToken);
        try
        {
            var current = Volatile.Read(ref _rules);
            removed = ids
                .Select(id => current.GetValueOrDefault(id))
                .Where(r => r is not null && r.Source != RuleSource.Builtin)
                .Cast<Rule>()
                .ToList();
            if (removed.Count == 0)
            {
                return 0;
            }

            var persistent = removed.Where(r => r.IsPersistent).ToList();
            if (persistent.Count > 0)
            {
                await repository.DeleteAsync(persistent, reason, time.GetUtcNow(), cancellationToken);
            }

            ImmutableInterlocked.Update(ref _rules, (d, r) => d.RemoveRange(r.Select(x => x.Id)), removed);
            foreach (var rule in removed)
            {
                _hits.TryRemove(rule.Id, out _);
            }

            Republish();
        }
        finally
        {
            _writeGate.Release();
        }

        foreach (var rule in removed)
        {
            Notify(new RuleChange(RuleChangeKind.Removed, rule, reason));
        }

        return removed.Count;
    }

    /// <summary>用當前的配置文件和時間，重新計算生效規則並發布。</summary>
    public void Republish()
    {
        var now = time.GetUtcNow();
        var profile = state.ActiveProfileId;
        var active = Volatile.Read(ref _rules).Values.Where(r =>
            r.Enabled
            && r.Lifetime != RuleLifetime.Once
            && r.IsLiveAt(now)
            && (r.ProfileId is null || r.ProfileId == profile));
        engine.Publish(active);
    }

    /// <summary>記錄一次命中（熱路徑，無鎖、無 I/O）。</summary>
    public void RecordHit(Guid ruleId)
    {
        if (_hits.TryGetValue(ruleId, out var counter))
        {
            counter.Hit(time.GetUtcNow());
        }
    }

    public (long Count, DateTimeOffset? LastHitAt) GetHitStats(Guid ruleId) =>
        _hits.TryGetValue(ruleId, out var c) ? (c.Total, c.LastHitAt) : (0, null);

    /// <summary>把未持久化的命中增量寫入存儲（由維護任務定期調用）。</summary>
    public async Task FlushHitsAsync(CancellationToken cancellationToken)
    {
        var rules = Volatile.Read(ref _rules);
        var deltas = new List<RuleHitDelta>();
        foreach (var (id, counter) in _hits)
        {
            if (rules.TryGetValue(id, out var rule) && rule.IsPersistent && counter.TakeDelta() is { } delta)
            {
                deltas.Add(new RuleHitDelta(id, delta.Count, delta.LastHitAt));
            }
        }

        if (deltas.Count > 0)
        {
            await repository.RecordHitsAsync(deltas, cancellationToken);
        }
    }

    private static void Validate(Rule rule)
    {
        if (rule.Lifetime == RuleLifetime.Timed && rule.ExpiresAt is null)
        {
            throw new ArgumentException("限時規則必須設置過期時間。", nameof(rule));
        }

        if (rule.Lifetime == RuleLifetime.UntilProcessExit && rule.BoundProcess is null)
        {
            throw new ArgumentException("「直到應用退出」規則必須綁定進程。", nameof(rule));
        }

        if (rule.App is { PathPattern: var p } && string.IsNullOrWhiteSpace(p))
        {
            throw new ArgumentException("應用路徑不能為空。", nameof(rule));
        }
    }

    private void Notify(RuleChange change)
    {
        Changed?.Invoke(this, change);
        Changes.Publish(change);
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "已加載 {Stored} 條持久化規則和 {Builtin} 條內置規則")]
    private static partial void LogLoaded(ILogger logger, int stored, int builtin);

    private sealed class HitCounter(long total, DateTimeOffset? lastHitAt)
    {
        private readonly Lock _gate = new();
        private long _total = total;
        private long _pending;
        private DateTimeOffset? _lastHitAt = lastHitAt;

        public long Total
        {
            get
            {
                lock (_gate)
                {
                    return _total;
                }
            }
        }

        public DateTimeOffset? LastHitAt
        {
            get
            {
                lock (_gate)
                {
                    return _lastHitAt;
                }
            }
        }

        public void Hit(DateTimeOffset now)
        {
            lock (_gate)
            {
                _total++;
                _pending++;
                _lastHitAt = now;
            }
        }

        public (long Count, DateTimeOffset LastHitAt)? TakeDelta()
        {
            lock (_gate)
            {
                if (_pending == 0 || _lastHitAt is null)
                {
                    return null;
                }

                var delta = (_pending, _lastHitAt.Value);
                _pending = 0;
                return delta;
            }
        }
    }
}
