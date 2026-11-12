using System.Diagnostics;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Pipeline;

/// <summary>
/// 判決管線（設計文檔 §5）：依次執行各判決階段，記錄日誌和指標，任何異常都按失效策略兜底。
/// </summary>
public sealed partial class VerdictPipeline
{
    private readonly IVerdictStage[] _stages;
    private readonly IAppIdentityResolver _resolver;
    private readonly DnsMapper _dns;
    private readonly IActivitySink _activity;
    private readonly SettingsService _settings;
    private readonly CoreMetrics _metrics;
    private readonly TimeProvider _time;
    private readonly ILogger<VerdictPipeline> _logger;

    public VerdictPipeline(
        IEnumerable<IVerdictStage> stages,
        IAppIdentityResolver resolver,
        DnsMapper dns,
        IActivitySink activity,
        SettingsService settings,
        CoreMetrics metrics,
        TimeProvider time,
        ILogger<VerdictPipeline> logger)
    {
        _stages = stages.OrderBy(s => s.Order).ToArray();
        _resolver = resolver;
        _dns = dns;
        _activity = activity;
        _settings = settings;
        _metrics = metrics;
        _time = time;
        _logger = logger;
    }

    public IReadOnlyList<IVerdictStage> Stages => _stages;

    public async ValueTask<Verdict> DecideAsync(FlowInfo flow, CancellationToken cancellationToken)
    {
        var started = Stopwatch.GetTimestamp();
        var context = new FlowContext(flow, _resolver, _dns, _time.GetUtcNow(), cancellationToken);
        Verdict verdict;

        try
        {
            verdict = await RunStagesAsync(context);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            verdict = FailSafe(VerdictReason.Cancelled);
        }
        catch (Exception ex)
        {
            LogPipelineError(_logger, ex, flow.FlowId);
            verdict = FailSafe(VerdictReason.FailSafe);
        }

        _metrics.RecordVerdict(verdict, Stopwatch.GetElapsedTime(started));
        Publish(context, verdict);
        return verdict;
    }

    private async ValueTask<Verdict> RunStagesAsync(FlowContext context)
    {
        foreach (var stage in _stages)
        {
            if (await stage.EvaluateAsync(context) is { } verdict)
            {
                return verdict;
            }
        }

        // 正常情況下兜底階段總會給出判決；走到這裡說明階段配置有誤
        return FailSafe(VerdictReason.FailSafe);
    }

    private Verdict FailSafe(VerdictReason reason) =>
        _settings.Current.FailPolicy == FailPolicy.FailClosed ? Verdict.Deny(reason) : Verdict.Allow(reason);

    private void Publish(FlowContext context, Verdict verdict)
    {
        var flow = context.Flow;
        var app = context.App;
        _activity.Publish(new ConnectionEvent(
            context.Now,
            app?.AppId,
            app?.Path ?? flow.ExecutablePath,
            app?.Name,
            flow.Direction,
            flow.Protocol,
            flow.Local,
            flow.Remote,
            context.Domains.Primary,
            verdict));
    }

    [LoggerMessage(Level = LogLevel.Error, Message = "判決管線處理連接 {FlowId} 時出錯，按失效策略處理")]
    private static partial void LogPipelineError(ILogger logger, Exception exception, ulong flowId);
}
