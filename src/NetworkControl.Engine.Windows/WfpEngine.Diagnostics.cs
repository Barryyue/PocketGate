using System.Globalization;
using System.Text;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Engine.Windows.Compilation;

namespace NetworkControl.Engine.Windows;

/// <summary>WFP 引擎的診斷信息：會話狀態、已下發的過濾器、跳過的規則、下發失敗記錄、應用 ID 兜底。</summary>
internal sealed partial class WfpEngine
{
    string IDiagnosticsContributor.Name => "engine";

    Task IDiagnosticsContributor.CollectAsync(IDiagnosticsSink sink, DiagnosticsCollectOptions options, CancellationToken cancellationToken)
    {
        var text = new StringBuilder();
        void Line(string name, object? value) => text.AppendLine(CultureInfo.InvariantCulture, $"{name,-28}{value}");

        var installed = _installedSnapshot;
        text.AppendLine("[WFP]");
        Line("Session open", _session is not null);
        Line("Net events subscribed", _eventsHandle != 0);
        Line("DNS (ETW) monitor", _dns is not null ? "running" : "not running");
        Line("Traffic (ETW) monitor", _traffic is not null ? $"running, {_traffic.EventCount} events" : "not running");
        Line("Collect net events changed", _previousCollectOption is { } previous ? $"yes (was {previous}, restored on stop)" : "no");
        Line("Last applied (UTC)", _lastAppliedAt?.ToString("u", CultureInfo.InvariantCulture) ?? "-");
        Line("Installed filters", installed.Length);
        foreach (var group in installed.GroupBy(f => f.Spec.Role).OrderBy(g => g.Key))
        {
            Line($"  {group.Key}", group.Count());
        }

        Line("Drop events seen", Interlocked.Read(ref _dropsSeen));
        Line("Drops reported to core", Interlocked.Read(ref _dropsReported));
        Line("Domain rule patterns", _domainPatterns.Length);
        Line("Observed app ids", _observedAppIds.Count);

        var skipped = _lastSkipped;
        text.AppendLine().AppendLine(CultureInfo.InvariantCulture, $"[Skipped rules: {skipped.Length}]");
        foreach (var rule in skipped)
        {
            text.AppendLine(CultureInfo.InvariantCulture, $"{rule.RuleId}  {rule.Reason}");
        }

        var failures = _filterFailures.ToArray();
        text.AppendLine().AppendLine(CultureInfo.InvariantCulture, $"[Filter failures (latest {failures.Length})]");
        foreach (var failure in failures)
        {
            text.AppendLine(failure);
        }

        sink.AddText("wfp-state.txt", text.ToString());
        sink.AddText("wfp-filters.tsv", DescribeFilters(installed));
        return Task.CompletedTask;
    }

    private static string DescribeFilters((FilterSpec Spec, ulong Id)[] installed)
    {
        var text = new StringBuilder("id\trole\taction\tweight\tfamily\tapp\tremote\tremote_port\tlocal_port\tprotocol\tloopback\trule_id\tname\n");
        foreach (var (spec, id) in installed.OrderByDescending(f => f.Spec.Weight))
        {
            text.AppendJoin('\t',
                    id.ToString(CultureInfo.InvariantCulture),
                    spec.Role.ToString(),
                    spec.Permit ? "permit" : "block",
                    spec.Weight.ToString(CultureInfo.InvariantCulture),
                    spec.Family.ToString(),
                    spec.AppPath ?? "*",
                    spec.RemoteNetworks.Count == 0 ? "*" : string.Join(',', spec.RemoteNetworks.Select(n => n.ToString())),
                    spec.RemotePort?.ToString() ?? "*",
                    spec.LocalPort?.ToString() ?? "*",
                    spec.Protocol?.ToString() ?? "*",
                    spec.LoopbackOnly ? "yes" : "",
                    spec.RuleId?.ToString() ?? "",
                    spec.Name)
                .Append('\n');
        }

        return text.ToString();
    }
}
