using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Rules;
using NetworkControl.Engine.Vpn.Packets;
using NetworkControl.Engine.Vpn.Routing;
using static NetworkControl.Engine.Vpn.Tests.TestApps;

namespace NetworkControl.Engine.Vpn.Tests;

/// <summary>快速失敗的報文構造：TCP RST（IPv4 / IPv6）與 ICMP 端口不可達，校驗和用解析器驗證。</summary>
public class FastFailPacketTests
{
    private const string Client4 = "10.111.222.1";
    private const string Remote4 = "140.82.112.3";
    private const string Client6 = "fd00:6e63::1";
    private const string Remote6 = "2606:4700::1111";

    [Fact]
    public void Tcp_reset4_swaps_endpoints_and_has_valid_checksums()
    {
        var reset = IpPacketBuilder.TcpReset(
            new IPEndPoint(IPAddress.Parse(Remote4), 443),
            new IPEndPoint(IPAddress.Parse(Client4), 40_000),
            acknowledgment: 0x1234_5679);

        Assert.Equal(40, reset.Length);
        Assert.True(IpPacketParser.TryParse(reset, out var info));
        Assert.Equal(L4Protocol.Tcp, info.Protocol);
        Assert.Equal(new IPEndPoint(IPAddress.Parse(Remote4), 443), info.Source);
        Assert.Equal(new IPEndPoint(IPAddress.Parse(Client4), 40_000), info.Destination);
        Assert.Equal(TcpFlags.Rst | TcpFlags.Ack, info.TcpFlags);
        Assert.Equal(0u, info.TcpSequence);
        Assert.Equal(0x1234_5679u, BinaryPrimitives.ReadUInt32BigEndian(reset.AsSpan(28))); // ack
        Assert.Equal(0, BinaryPrimitives.ReadUInt16BigEndian(reset.AsSpan(34))); // 窗口

        Assert.Equal(0, IpPacketBuilder.Checksum(reset.AsSpan(0, 20), 0)); // IP 頭
        Assert.Equal(0, IpPacketBuilder.Checksum(reset.AsSpan(20), Sum(reset.AsSpan(12, 8)) + 6u + 20u)); // TCP 偽首部
    }

    [Fact]
    public void Tcp_reset6_swaps_endpoints_and_has_valid_checksum()
    {
        var reset = IpPacketBuilder.TcpReset(
            new IPEndPoint(IPAddress.Parse(Remote6), 443),
            new IPEndPoint(IPAddress.Parse(Client6), 41_000),
            acknowledgment: 77);

        Assert.Equal(60, reset.Length);
        Assert.True(IpPacketParser.TryParse(reset, out var info));
        Assert.Equal(new IPEndPoint(IPAddress.Parse(Remote6), 443), info.Source);
        Assert.Equal(new IPEndPoint(IPAddress.Parse(Client6), 41_000), info.Destination);
        Assert.Equal(TcpFlags.Rst | TcpFlags.Ack, info.TcpFlags);
        Assert.Equal(77u, BinaryPrimitives.ReadUInt32BigEndian(reset.AsSpan(48))); // ack

        Assert.Equal(0, IpPacketBuilder.Checksum(reset.AsSpan(40), Sum(reset.AsSpan(8, 32)) + 6u + 20u)); // 偽首部
    }

    [Fact]
    public void Icmp_unreachable4_quotes_ip_header_and_eight_bytes()
    {
        var original = Packets.Syn(Client4, 40_000, Remote4, 443);
        var icmp = IpPacketBuilder.IcmpPortUnreachable(
            new IPEndPoint(IPAddress.Parse(Remote4), 443),
            new IPEndPoint(IPAddress.Parse(Client4), 40_000),
            original);

        Assert.Equal(20 + 8 + 28, icmp.Length);
        Assert.True(IpPacketParser.TryParse(icmp, out var info));
        Assert.Equal(L4Protocol.Icmp, info.Protocol);
        Assert.Equal(IPAddress.Parse(Remote4), info.Source.Address);
        Assert.Equal(IPAddress.Parse(Client4), info.Destination.Address); // ICMP 沒有端口
        Assert.Equal(3, icmp[20]); // type: destination unreachable
        Assert.Equal(3, icmp[21]); // code: port unreachable
        Assert.True(icmp.AsSpan(28).SequenceEqual(original.AsSpan(0, 28)));
        Assert.Equal(0, IpPacketBuilder.Checksum(icmp.AsSpan(20), 0));
    }

    [Fact]
    public void Icmp_unreachable6_quotes_as_much_as_fits_in_1280_bytes()
    {
        var udp = new byte[1400];
        BinaryPrimitives.WriteUInt16BigEndian(udp, 50_000);
        BinaryPrimitives.WriteUInt16BigEndian(udp.AsSpan(2), 443);
        BinaryPrimitives.WriteUInt16BigEndian(udp.AsSpan(4), (ushort)udp.Length);
        var original = Packets.Ipv6(Client6, Remote6, Packets.Udp, udp);

        var icmp = IpPacketBuilder.IcmpPortUnreachable(
            new IPEndPoint(IPAddress.Parse(Remote6), 443),
            new IPEndPoint(IPAddress.Parse(Client6), 50_000),
            original);

        Assert.Equal(1280, icmp.Length);
        Assert.Equal(1280 - 40 - 8, icmp.Length - 48);
        Assert.Equal(1, icmp[40]); // type: destination unreachable
        Assert.Equal(4, icmp[41]); // code: port unreachable
        Assert.True(icmp.AsSpan(48).SequenceEqual(original.AsSpan(0, 1232)));
        Assert.Equal(0, IpPacketBuilder.Checksum(icmp.AsSpan(40), Sum(icmp.AsSpan(8, 32)) + 58u + (uint)icmp.AsSpan(40).Length));
    }

    private static uint Sum(ReadOnlySpan<byte> data)
    {
        uint sum = 0;
        var i = 0;
        for (; i + 1 < data.Length; i += 2)
        {
            sum += BinaryPrimitives.ReadUInt16BigEndian(data[i..]);
        }

        if (i < data.Length)
        {
            sum += (uint)(data[i] << 8);
        }

        return sum;
    }
}

/// <summary>阻斷上報器：只有明確拒絕時才回 RST / ICMP，等待詢問保持黑洞，並按（UID, 遠端）限速。</summary>
public class FastFailReporterTests
{
    private const string Client4 = "10.111.222.1";
    private const string Remote4 = "140.82.112.3";

    private readonly FakeTimeProvider _time = new(T0);
    private readonly FakeOwnerResolver _owners = new();
    private readonly FakeAppCatalog _catalog = new(Chrome, Firefox);
    private VpnRoutingPlan _plan = VpnRoutingPlan.Empty;

    private BlockedFlowReporter CreateReporter(bool fastFail = true) =>
        new(_owners, _catalog, () => _plan, new VpnEngineCounters(), _time, TimeSpan.FromSeconds(5), NullLogger.Instance, fastFail: fastFail);

    private void UsePlan(FilterMode mode, params Rule[] rules) =>
        _plan = VpnRoutingPlanner.Plan(new VpnRoutingInput
        {
            Rules = RuleSetOf(rules),
            Mode = mode,
            Apps = _catalog.GetApps(),
            SelfPackage = SelfPackage,
        });

    [Fact]
    public void Rule_denied_tcp_gets_reset()
    {
        _owners.UidByLocalPort[40_000] = Chrome.Uid;
        UsePlan(FilterMode.Ask, Rule(VerdictAction.Deny, MatcherFor(Chrome)));
        var reporter = CreateReporter();
        var writer = new RecordingWriter();

        reporter.OnPacket(Packets.Syn(Client4, 40_000, Remote4, 443, sequence: 41), writer);

        var reset = Assert.Single(writer.Packets);
        Assert.True(IpPacketParser.TryParse(reset, out var info));
        Assert.Equal(TcpFlags.Rst | TcpFlags.Ack, info.TcpFlags);
        Assert.Equal(new IPEndPoint(IPAddress.Parse(Remote4), 443), info.Source);
        Assert.Equal(42u, BinaryPrimitives.ReadUInt32BigEndian(reset.AsSpan(28)));
        Assert.Equal(1, reporter.Counters.ResetsSent);
        Assert.Equal(1, reporter.Counters.Attempts);
        Assert.Equal(0, reporter.Counters.UnreachablesSent);
    }

    [Fact]
    public void Waiting_for_prompt_keeps_black_hole()
    {
        _owners.UidByLocalPort[40_000] = Chrome.Uid;
        UsePlan(FilterMode.Ask);
        var reporter = CreateReporter();
        var writer = new RecordingWriter();

        reporter.OnPacket(Packets.Syn(Client4, 40_000, Remote4, 443), writer);

        Assert.Empty(writer.Packets);
        Assert.Equal(0, reporter.Counters.ResetsSent);
        Assert.Equal(1, reporter.Counters.Attempts);
    }

    [Theory]
    [InlineData(FilterMode.Whitelist)]
    [InlineData(FilterMode.BlockAll)]
    public void Mode_level_deny_gets_reset(FilterMode mode)
    {
        _owners.UidByLocalPort[40_000] = Chrome.Uid;
        UsePlan(mode);
        var reporter = CreateReporter();
        var writer = new RecordingWriter();

        reporter.OnPacket(Packets.Syn(Client4, 40_000, Remote4, 443), writer);

        Assert.Single(writer.Packets);
        Assert.Equal(1, reporter.Counters.ResetsSent);
    }

    [Fact]
    public void Rate_limits_one_response_per_uid_and_remote_per_second()
    {
        _owners.UidByLocalPort[40_000] = Chrome.Uid;
        _owners.UidByLocalPort[40_001] = Chrome.Uid;
        UsePlan(FilterMode.Blacklist, Rule(VerdictAction.Deny, MatcherFor(Chrome)));
        var reporter = CreateReporter();
        var writer = new RecordingWriter();

        reporter.OnPacket(Packets.Syn(Client4, 40_000, Remote4, 443), writer);
        reporter.OnPacket(Packets.Syn(Client4, 40_001, Remote4, 443), writer); // 重試：1 秒內不再回應
        Assert.Single(writer.Packets);

        // 不同遠端不受限制
        reporter.OnPacket(Packets.Syn(Client4, 40_001, "1.1.1.1", 443), writer);
        Assert.Equal(2, writer.Packets.Count);

        _time.Advance(TimeSpan.FromSeconds(1));
        reporter.OnPacket(Packets.Syn(Client4, 40_000, Remote4, 443), writer);
        Assert.Equal(3, writer.Packets.Count);
        Assert.Equal(3, reporter.Counters.ResetsSent);
    }

    [Fact]
    public void Denied_udp_gets_icmp_port_unreachable()
    {
        _owners.UidByLocalPort[53_000] = Chrome.Uid;
        UsePlan(FilterMode.BlockAll);
        var reporter = CreateReporter();
        var writer = new RecordingWriter();

        reporter.OnPacket(Packets.Ipv4(Client4, Remote4, Packets.Udp, Packets.UdpDatagram(53_000, 443)), writer);

        var icmp = Assert.Single(writer.Packets);
        Assert.Equal(3, icmp[20]);
        Assert.Equal(3, icmp[21]);
        Assert.Equal(1, reporter.Counters.UnreachablesSent);
        Assert.Equal(0, reporter.Counters.ResetsSent);
    }

    [Fact]
    public void Ipv6_denied_tcp_gets_reset6()
    {
        _owners.UidByLocalPort[41_000] = Chrome.Uid;
        UsePlan(FilterMode.BlockAll);
        var reporter = CreateReporter();
        var writer = new RecordingWriter();

        reporter.OnPacket(Packets.Syn("fd00:6e63::1", 41_000, "2606:4700::1111", 443), writer);

        var reset = Assert.Single(writer.Packets);
        Assert.True(IpPacketParser.TryParse(reset, out var info));
        Assert.Equal(TcpFlags.Rst | TcpFlags.Ack, info.TcpFlags);
        Assert.Equal(AddressFamily.InterNetworkV6, info.Source.AddressFamily);
    }

    [Fact]
    public void Fast_fail_can_be_disabled()
    {
        _owners.UidByLocalPort[40_000] = Chrome.Uid;
        UsePlan(FilterMode.BlockAll);
        var reporter = CreateReporter(fastFail: false);
        var writer = new RecordingWriter();

        reporter.OnPacket(Packets.Syn(Client4, 40_000, Remote4, 443), writer);

        Assert.Empty(writer.Packets);
        Assert.Equal(0, reporter.Counters.ResetsSent);
    }

    [Fact]
    public void Unknown_and_system_uids_are_not_answered()
    {
        _owners.UidByLocalPort[40_001] = 1051; // 系統核心 UID
        UsePlan(FilterMode.BlockAll);
        var reporter = CreateReporter();
        var writer = new RecordingWriter();

        reporter.OnPacket(Packets.Syn(Client4, 40_000, Remote4, 443), writer); // 查不到 UID
        reporter.OnPacket(Packets.Syn(Client4, 40_001, Remote4, 443), writer); // 系統 UID

        Assert.Empty(writer.Packets);
    }
}
