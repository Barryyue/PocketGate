using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;
using NetworkControl.Engine.Vpn.Packets;
using NetworkControl.Engine.Vpn.Routing;
using static NetworkControl.Engine.Vpn.Tests.TestApps;

namespace NetworkControl.Engine.Vpn.Tests;

public class BlockedFlowReporterTests
{
    private readonly FakeTimeProvider _time = new(T0);
    private readonly FakeOwnerResolver _owners = new();
    private readonly FakeAppCatalog _catalog = new(Chrome, Firefox);
    private readonly RecordingEngineHost _host = new();
    private VpnRoutingPlan _plan = VpnRoutingPlan.Empty;

    private BlockedFlowReporter CreateReporter() =>
        new(_owners, _catalog, () => _plan, new VpnEngineCounters(), _time, TimeSpan.FromSeconds(5), NullLogger.Instance);

    private static PacketInfo Parse(byte[] packet)
    {
        Assert.True(IpPacketParser.TryParse(packet, out var info));
        return info;
    }

    [Fact]
    public void Reports_blocked_flow_with_package_path()
    {
        _owners.UidByLocalPort[40_000] = Chrome.Uid;
        var reporter = CreateReporter();

        var flow = reporter.Process(_host, Parse(Packets.Syn("10.111.222.1", 40_000, "140.82.112.3", 443)));

        Assert.NotNull(flow);
        Assert.Equal("android-app://com.android.chrome", flow.ExecutablePath);
        Assert.Equal(FlowOrigin.BlockedEvent, flow.Origin);
        Assert.Equal(Direction.Outbound, flow.Direction);
        Assert.Equal(443, flow.Remote.Port);
        Assert.Null(flow.Process);
        Assert.Equal(BlockReason.DefaultBlock, Assert.Single(_host.Blocked).Reason);
    }

    [Fact]
    public void Deduplicates_repeated_attempts_within_window()
    {
        _owners.UidByLocalPort[40_000] = Chrome.Uid;
        _owners.UidByLocalPort[40_001] = Chrome.Uid;
        var reporter = CreateReporter();

        reporter.Process(_host, Parse(Packets.Syn("10.111.222.1", 40_000, "1.1.1.1", 443)));
        // 重試（不同源端口、相同目標）
        reporter.Process(_host, Parse(Packets.Syn("10.111.222.1", 40_001, "1.1.1.1", 443)));
        Assert.Single(_host.Blocked);
        Assert.Equal(1, reporter.Counters.Deduplicated);

        // 不同目標照常上報
        reporter.Process(_host, Parse(Packets.Syn("10.111.222.1", 40_001, "8.8.8.8", 443)));
        Assert.Equal(2, _host.Blocked.Count);

        // 窗口過後再次上報
        _time.Advance(TimeSpan.FromSeconds(6));
        reporter.Process(_host, Parse(Packets.Syn("10.111.222.1", 40_000, "1.1.1.1", 443)));
        Assert.Equal(3, _host.Blocked.Count);
    }

    [Fact]
    public void Unknown_owner_is_counted_but_not_reported()
    {
        var reporter = CreateReporter();
        Assert.Null(reporter.Process(_host, Parse(Packets.Syn("10.111.222.1", 40_000, "1.1.1.1", 443))));
        Assert.Empty(_host.Blocked);
        Assert.Equal(1, reporter.Counters.UnknownOwner);
    }

    [Fact]
    public void Uid_without_package_uses_uid_path()
    {
        _owners.UidByLocalPort[40_000] = 10_999;
        var flow = CreateReporter().Process(_host, Parse(Packets.Syn("10.111.222.1", 40_000, "1.1.1.1", 443)));
        Assert.Equal("android-uid://10999", flow?.ExecutablePath);
    }

    [Fact]
    public void Apps_blocked_by_rule_are_reported_as_user_rule()
    {
        _owners.UidByLocalPort[40_000] = Firefox.Uid;
        _plan = VpnRoutingPlanner.Plan(new VpnRoutingInput
        {
            Rules = RuleSetOf(Rule(VerdictAction.Deny, MatcherFor(Firefox))),
            Mode = FilterMode.Ask,
            Apps = _catalog.GetApps(),
            SelfPackage = SelfPackage,
        });

        CreateReporter().Process(_host, Parse(Packets.Syn("10.111.222.1", 40_000, "1.1.1.1", 443)));
        Assert.Equal(BlockReason.UserRule, Assert.Single(_host.Blocked).Reason);
    }

    [Fact]
    public void Sink_ignores_non_attempts_and_garbage()
    {
        var reporter = CreateReporter();
        reporter.OnPacket(Packets.Ipv4("10.111.222.1", "1.1.1.1", Packets.Tcp, Packets.TcpSegment(40_000, 443, 0x10)));
        reporter.OnPacket([0x45, 0x00]);
        reporter.OnPacket(Packets.Syn("10.111.222.1", 40_000, "1.1.1.1", 443));

        Assert.Equal(3, reporter.Counters.Packets);
        Assert.Equal(1, reporter.Counters.Unparsed);
        Assert.Equal(1, reporter.Counters.Attempts);
    }
}
