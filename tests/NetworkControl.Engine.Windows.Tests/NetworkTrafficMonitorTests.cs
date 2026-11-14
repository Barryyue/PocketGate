using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Rules;
using NetworkControl.Engine.Windows.Traffic;

namespace NetworkControl.Engine.Windows.Tests;

public sealed class NetworkTrafficMonitorTests
{
    [Theory]
    [InlineData(10, false)]
    [InlineData(11, true)]
    [InlineData(26, false)]
    [InlineData(27, true)]
    [InlineData(42, false)]
    [InlineData(43, true)]
    [InlineData(58, false)]
    [InlineData(59, true)]
    public void Classifies_send_and_receive_events(int eventId, bool received) =>
        Assert.Equal(received, NetworkTrafficMonitor.Classify(eventId));

    [Theory]
    [InlineData(12)] // TCP 連接事件
    [InlineData(15)]
    [InlineData(0)]
    public void Ignores_other_events(int eventId) => Assert.Null(NetworkTrafficMonitor.Classify(eventId));

    [Fact]
    public void Aggregates_per_process_and_resolves_paths_on_flush()
    {
        var host = new RecordingHost();
        var processes = new Processes();
        var monitor = new NetworkTrafficMonitor(processes, new FakeTimeProvider(), NullLogger.Instance);
        monitor.Attach(host);

        monitor.Add(100, received: true, 1500);
        monitor.Add(100, received: true, 500);
        monitor.Add(100, received: false, 40);
        monitor.Add(200, received: true, 7); // 已退出的進程
        monitor.Add(0, received: true, 99);  // 無效 PID 忽略
        monitor.Flush();

        Assert.Equal(2, host.Samples.Count);
        var known = Assert.Single(host.Samples, s => s.ExecutablePath is not null);
        Assert.Equal(@"C:\Apps\curl.exe", known.ExecutablePath);
        Assert.Equal(2000, known.BytesIn);
        Assert.Equal(40, known.BytesOut);
        Assert.Null(Assert.Single(host.Samples, s => s.ExecutablePath is null).Process);

        monitor.Flush(); // 沒有新數據不上報
        Assert.Equal(2, host.Samples.Count);
    }

    private sealed class Processes : IProcessInfoProvider
    {
        public bool TryGetProcess(int pid, out ProcessSnapshot snapshot)
        {
            snapshot = new ProcessSnapshot(new ProcessRef(pid, 1), @"C:\Apps\curl.exe");
            return pid == 100;
        }

        public bool IsAlive(ProcessRef process) => process.Pid == 100;
    }

    private sealed class RecordingHost : IEngineHost
    {
        public List<TrafficSample> Samples { get; } = [];

        public RuleEngine Rules => null!;

        public FilterMode CurrentMode => FilterMode.Ask;

        public FailPolicy FailPolicy => FailPolicy.FailOpen;

        public ValueTask<VerdictAction> DecideAsync(FlowInfo flow) => ValueTask.FromResult(VerdictAction.Allow);

        public void OnFlowBlocked(FlowInfo flow, BlockReason reason)
        {
        }

        public void OnDnsObserved(DnsObservation observation)
        {
        }

        public void OnTraffic(ReadOnlySpan<TrafficSample> samples) => Samples.AddRange(samples.ToArray());

        public void OnEngineFault(EngineFault fault)
        {
        }
    }
}
