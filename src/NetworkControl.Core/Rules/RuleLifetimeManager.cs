using System.Collections.Concurrent;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Rules;

/// <summary>
/// 規則生命週期管理（設計文檔 §7）：
/// 限時規則到期移除；「直到應用退出」規則在進程退出時移除；定期把命中統計寫回存儲。
/// 「直到重啟」規則只存在內存中，服務重啟後自然消失，無需處理。
/// </summary>
public sealed partial class RuleLifetimeManager(
    RuleService rules,
    IProcessExitWatcher exitWatcher,
    IProcessInfoProvider processes,
    TimeProvider time,
    ILogger<RuleLifetimeManager> logger) : BackgroundService
{
    private static readonly TimeSpan Tick = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan HitFlushInterval = TimeSpan.FromSeconds(60);

    private readonly ConcurrentDictionary<Guid, IDisposable> _watches = new();

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        rules.Changed += OnRuleChanged;
        try
        {
            await AttachExistingProcessRulesAsync(stoppingToken);

            using var timer = new PeriodicTimer(Tick, time);
            var lastFlush = time.GetUtcNow();
            while (await timer.WaitForNextTickAsync(stoppingToken))
            {
                await RemoveExpiredAsync(stoppingToken);

                if (time.GetUtcNow() - lastFlush >= HitFlushInterval)
                {
                    await rules.FlushHitsAsync(stoppingToken);
                    lastFlush = time.GetUtcNow();
                }
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // 正常停止
        }
        finally
        {
            rules.Changed -= OnRuleChanged;
            foreach (var watch in _watches.Values)
            {
                watch.Dispose();
            }

            _watches.Clear();
            await rules.FlushHitsAsync(CancellationToken.None);
        }
    }

    private async Task RemoveExpiredAsync(CancellationToken cancellationToken)
    {
        var now = time.GetUtcNow();
        var expired = rules.All.Where(r => !r.IsLiveAt(now)).Select(r => r.Id).ToArray();
        if (expired.Length > 0)
        {
            var count = await rules.RemoveAsync(expired, RuleEndReason.Expired, cancellationToken);
            LogExpired(logger, count);
        }
    }

    private async Task AttachExistingProcessRulesAsync(CancellationToken cancellationToken)
    {
        var dead = new List<Guid>();
        foreach (var rule in rules.All.Where(r => r.BoundProcess is not null))
        {
            if (processes.IsAlive(rule.BoundProcess!.Value))
            {
                Attach(rule);
            }
            else
            {
                dead.Add(rule.Id);
            }
        }

        if (dead.Count > 0)
        {
            await rules.RemoveAsync(dead, RuleEndReason.ProcessExited, cancellationToken);
        }
    }

    private void OnRuleChanged(object? sender, RuleChange change)
    {
        switch (change.Kind)
        {
            case RuleChangeKind.Added when change.Rule.BoundProcess is not null:
                Attach(change.Rule);
                break;
            case RuleChangeKind.Removed when _watches.TryRemove(change.Rule.Id, out var watch):
                watch.Dispose();
                break;
        }
    }

    private void Attach(Rule rule)
    {
        var ruleId = rule.Id;
        var watch = exitWatcher.Watch(rule.BoundProcess!.Value, () => _ = OnProcessExitedAsync(ruleId));
        if (!_watches.TryAdd(ruleId, watch))
        {
            watch.Dispose();
        }
    }

    private async Task OnProcessExitedAsync(Guid ruleId)
    {
        try
        {
            await rules.RemoveAsync([ruleId], RuleEndReason.ProcessExited, CancellationToken.None);
        }
        catch (Exception ex)
        {
            LogRemoveFailed(logger, ex, ruleId);
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "移除了 {Count} 條已過期的規則")]
    private static partial void LogExpired(ILogger logger, int count);

    [LoggerMessage(Level = LogLevel.Error, Message = "進程退出後移除規則 {RuleId} 失敗")]
    private static partial void LogRemoveFailed(ILogger logger, Exception exception, Guid ruleId);
}
