using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Prompts;

/// <summary>勿擾期間某個應用的阻斷統計。</summary>
public sealed record QuietPeriodApp(long? AppId, string? Path, string? Name, int Allowed, int Denied);

/// <summary>一次勿擾期間的彙總；<see cref="Apps"/> 為空時不生成。</summary>
public sealed record QuietPeriodSummary(
    Guid Id,
    DateTimeOffset From,
    DateTimeOffset To,
    IReadOnlyList<QuietPeriodApp> Apps)
{
    public long Allowed => Apps.Sum(a => (long)a.Allowed);

    public long Denied => Apps.Sum(a => (long)a.Denied);
}

/// <summary>
/// 統計勿擾期間按默認動作處理的連接。勿擾結束（手動關閉或到期）後生成一份彙總，
/// 由界面彈出通知；摘要只保留最近一份，服務重啟後丟失可以接受。
/// </summary>
public sealed partial class QuietPeriodTracker(
    RuntimeStateService state,
    TimeProvider time,
    ILogger<QuietPeriodTracker> logger) : BackgroundService, IActivityConsumer
{
    private static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(5);

    private readonly Lock _gate = new();
    private readonly Dictionary<(long? AppId, string? Path), QuietPeriodApp> _apps = [];
    private QuietPeriodSummary? _last;
    private DateTimeOffset _from;
    private bool _active;

    public event EventHandler<QuietPeriodSummary>? SummaryReady;

    public QuietPeriodSummary? LastSummary => Volatile.Read(ref _last);

    public void Consume(ConnectionEvent connectionEvent)
    {
        if (connectionEvent.Verdict.Reason != VerdictReason.DoNotDisturb)
        {
            return;
        }

        var now = connectionEvent.Timestamp;
        lock (_gate)
        {
            if (!_active)
            {
                if (!state.IsDoNotDisturb)
                {
                    return;
                }

                Start(now);
            }

            var key = (connectionEvent.AppId, connectionEvent.AppPath);
            var existing = _apps.GetValueOrDefault(key);
            var allowed = existing?.Allowed ?? 0;
            var denied = existing?.Denied ?? 0;
            if (connectionEvent.Verdict.Action == VerdictAction.Allow)
            {
                allowed++;
            }
            else
            {
                denied++;
            }

            _apps[key] = new QuietPeriodApp(
                connectionEvent.AppId,
                connectionEvent.AppPath,
                connectionEvent.AppName,
                allowed,
                denied);
        }
    }

    public void Dismiss()
    {
        Volatile.Write(ref _last, null);
    }

    public override Task StartAsync(CancellationToken cancellationToken)
    {
        state.Changed += OnStateChanged;
        lock (_gate)
        {
            if (state.IsDoNotDisturb)
            {
                Start(time.GetUtcNow());
            }
        }

        return base.StartAsync(cancellationToken);
    }

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        state.Changed -= OnStateChanged;
        await base.StopAsync(cancellationToken);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(PollInterval, time);
        try
        {
            while (await timer.WaitForNextTickAsync(stoppingToken))
            {
                CheckState();
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // 停止
        }
    }

    private void OnStateChanged(object? sender, EventArgs e) => CheckState();

    private void CheckState()
    {
        QuietPeriodSummary? summary = null;
        lock (_gate)
        {
            if (state.IsDoNotDisturb)
            {
                if (!_active)
                {
                    Start(time.GetUtcNow());
                }

                return;
            }

            if (_active)
            {
                summary = Finish();
            }
        }

        if (summary is not null)
        {
            Volatile.Write(ref _last, summary);
            SummaryReady?.Invoke(this, summary);
            LogSummary(logger, summary.Apps.Count, summary.Denied);
        }
    }

    private void Start(DateTimeOffset now)
    {
        _active = true;
        _from = now;
        _apps.Clear();
    }

    private QuietPeriodSummary? Finish()
    {
        _active = false;
        if (_apps.Count == 0)
        {
            return null;
        }

        var summary = new QuietPeriodSummary(
            Guid.NewGuid(),
            _from,
            time.GetUtcNow(),
            _apps.Values.OrderByDescending(a => a.Denied + a.Allowed).ToArray());
        _apps.Clear();
        return summary;
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "勿擾結束：{Apps} 個應用、{Denied} 次連線被拒絕")]
    private static partial void LogSummary(ILogger logger, int apps, long denied);
}
