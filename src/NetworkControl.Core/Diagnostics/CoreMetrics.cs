using System.Diagnostics;
using System.Diagnostics.Metrics;
using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Diagnostics;

/// <summary>
/// 核心指標（設計文檔 §19）。通過 System.Diagnostics.Metrics 暴露，可用 dotnet-counters 查看：
/// <c>dotnet-counters monitor -n NetworkControl.Service --counters NetworkControl.Core</c>
/// </summary>
public sealed class CoreMetrics : IDisposable
{
    public const string MeterName = "NetworkControl.Core";

    private readonly Meter _meter;
    private readonly Counter<long> _verdicts;
    private readonly Histogram<double> _latency;
    private long _pendingPrompts;

    public CoreMetrics(IMeterFactory meterFactory)
    {
        _meter = meterFactory.Create(MeterName);
        _verdicts = _meter.CreateCounter<long>("netcontrol.verdicts", description: "判決總數");
        _latency = _meter.CreateHistogram<double>("netcontrol.verdict.duration", unit: "ms", description: "判決耗時（含等待用戶）");
        _meter.CreateObservableGauge("netcontrol.prompts.pending_flows", () => Interlocked.Read(ref _pendingPrompts), description: "等待用戶決策的連接數");
    }

    public long PendingPromptFlows => Interlocked.Read(ref _pendingPrompts);

    public void RecordVerdict(Verdict verdict, TimeSpan elapsed)
    {
        var tags = new TagList
        {
            { "action", verdict.Action == VerdictAction.Allow ? "allow" : "deny" },
            { "reason", verdict.Reason.ToString() },
        };
        _verdicts.Add(1, tags);
        _latency.Record(elapsed.TotalMilliseconds, tags);
    }

    public void SetPendingPromptFlows(long count) => Interlocked.Exchange(ref _pendingPrompts, count);

    public void Dispose() => _meter.Dispose();
}
