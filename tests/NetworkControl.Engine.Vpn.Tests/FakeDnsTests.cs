using System.Buffers.Binary;
using System.Net;
using System.Text;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Domain;
using NetworkControl.Engine.Vpn.Dns;
using NetworkControl.Engine.Vpn.Packets;
using NetworkControl.Engine.Vpn.Platform;
using NetworkControl.Engine.Vpn.Routing;
using static NetworkControl.Engine.Vpn.Tests.TestApps;

namespace NetworkControl.Engine.Vpn.Tests;

/// <summary>假 DNS：被阻斷的應用解析成功後才會發起連接，引擎才能按 UID 歸屬並詢問。</summary>
public class FakeDnsTests
{
    private const string Client = "10.111.222.1";

    /// <summary>構造 DNS 查詢報文。</summary>
    internal static byte[] Query(string name, ushort type, ushort id = 0x1234)
    {
        var message = new List<byte> { (byte)(id >> 8), (byte)id, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0 };
        foreach (var label in name.Split('.'))
        {
            message.Add((byte)label.Length);
            message.AddRange(Encoding.ASCII.GetBytes(label));
        }

        message.AddRange([0, (byte)(type >> 8), (byte)type, 0, 1]);
        return [.. message];
    }

    internal static byte[] DnsPacket(byte[] query, ushort sourcePort = 50_000)
    {
        var udp = new byte[8 + query.Length];
        BinaryPrimitives.WriteUInt16BigEndian(udp, sourcePort);
        BinaryPrimitives.WriteUInt16BigEndian(udp.AsSpan(2), 53);
        BinaryPrimitives.WriteUInt16BigEndian(udp.AsSpan(4), (ushort)udp.Length);
        query.CopyTo(udp, 8);
        return Packets.Ipv4(Client, VpnTunnelConfig.DnsServer.ToString(), Packets.Udp, udp);
    }

    [Fact]
    public void Parses_query()
    {
        Assert.True(DnsWire.TryParseQuery(Query("WWW.Example.com", DnsWire.TypeA), out var q));
        Assert.Equal("www.example.com", q.Name);
        Assert.Equal(DnsWire.TypeA, q.Type);
        Assert.Equal(0x1234, q.Id);
        Assert.True(q.RecursionDesired);
    }

    [Fact]
    public void Rejects_responses_and_garbage()
    {
        var response = Query("example.com", DnsWire.TypeA);
        response[2] |= 0x80;
        Assert.False(DnsWire.TryParseQuery(response, out _));
        Assert.False(DnsWire.TryParseQuery(new byte[5], out _));

        var truncated = Query("example.com", DnsWire.TypeA);
        Assert.False(DnsWire.TryParseQuery(truncated.AsSpan(0, truncated.Length - 3), out _));
    }

    [Fact]
    public void Pool_allocates_stable_addresses_and_reverses_them()
    {
        var pool = new FakeDnsPool();
        var a = pool.Allocate("a.example");
        var b = pool.Allocate("b.example");

        Assert.NotEqual(a, b);
        Assert.Equal(a, pool.Allocate("a.example"));
        Assert.True(FakeDnsPool.Contains(a));
        Assert.True(pool.TryGetName(b, out var name));
        Assert.Equal("b.example", name);
        Assert.False(pool.TryGetName(IPAddress.Parse("1.1.1.1"), out _));
    }

    [Fact]
    public void Pool_wraps_around_and_forgets_oldest()
    {
        var pool = new FakeDnsPool(capacity: 3);
        var first = pool.Allocate("one");
        pool.Allocate("two");
        var third = pool.Allocate("three"); // 覆蓋 one

        Assert.Equal(first, third);
        Assert.True(pool.TryGetName(third, out var name));
        Assert.Equal("three", name);
    }

    [Fact]
    public void Answers_a_query_with_fake_address_and_reports_hostname_on_connect()
    {
        var owners = new FakeOwnerResolver();
        var host = new RecordingEngineHost();
        var reporter = new BlockedFlowReporter(owners, new FakeAppCatalog(Chrome), () => VpnRoutingPlan.Empty, new VpnEngineCounters(),
            new FakeTimeProvider(T0), TimeSpan.FromSeconds(5), NullLogger.Instance);
        var writer = new RecordingWriter();

        reporter.OnPacket(DnsPacket(Query("api.github.com", DnsWire.TypeA)), writer);

        var response = Assert.Single(writer.Packets);
        Assert.True(IpPacketParser.TryParse(response, out var info));
        Assert.Equal(new IPEndPoint(VpnTunnelConfig.DnsServer, 53), info.Source);
        Assert.Equal(new IPEndPoint(IPAddress.Parse(Client), 50_000), info.Destination);
        Assert.Equal(0, IpPacketBuilder.Checksum(response.AsSpan(0, 20), 0)); // IP 頭校驗和正確

        var message = response.AsSpan(info.PayloadOffset, info.PayloadLength);
        Assert.Equal(0x1234, BinaryPrimitives.ReadUInt16BigEndian(message));
        Assert.Equal(1, BinaryPrimitives.ReadUInt16BigEndian(message[6..])); // 一條答案
        var fake = new IPAddress(message[^4..]);
        Assert.True(FakeDnsPool.Contains(fake));
        Assert.Equal(1, reporter.Counters.DnsAnswered);
        Assert.Equal(0, reporter.Counters.Attempts); // DNS 查詢本身不算聯網嘗試

        // 應用隨後連向假地址：上報的連接帶上域名
        owners.UidByLocalPort[40_000] = Chrome.Uid;
        Assert.True(IpPacketParser.TryParse(Packets.Syn(Client, 40_000, fake.ToString(), 443), out var syn));
        var flow = reporter.Process(host, syn);
        Assert.Equal("api.github.com", flow?.EngineHostname);
        Assert.Equal(L4Protocol.Tcp, flow?.Protocol);
    }

    [Fact]
    public void Non_a_queries_get_empty_answers()
    {
        var reporter = new BlockedFlowReporter(new FakeOwnerResolver(), new FakeAppCatalog(), () => VpnRoutingPlan.Empty, new VpnEngineCounters(),
            new FakeTimeProvider(T0), TimeSpan.FromSeconds(5), NullLogger.Instance);
        var writer = new RecordingWriter();

        reporter.OnPacket(DnsPacket(Query("example.com", DnsWire.TypeAaaa)), writer);

        Assert.True(IpPacketParser.TryParse(Assert.Single(writer.Packets), out var info));
        var message = Assert.Single(writer.Packets).AsSpan(info.PayloadOffset, info.PayloadLength);
        Assert.Equal(0, BinaryPrimitives.ReadUInt16BigEndian(message[6..]));
        Assert.Equal(0x8180, BinaryPrimitives.ReadUInt16BigEndian(message[2..]));
    }

    [Fact]
    public void Udp_checksum_is_valid()
    {
        var packet = IpPacketBuilder.Udp4(new IPEndPoint(IPAddress.Parse("10.0.0.1"), 53), new IPEndPoint(IPAddress.Parse("10.0.0.2"), 5000), [1, 2, 3]);
        // 偽首部 + UDP 段的校驗和為 0 表示正確
        uint pseudo = 0x0A00 + 0x0001 + 0x0A00 + 0x0002 + 17u + 11u;
        Assert.Equal(0, IpPacketBuilder.Checksum(packet.AsSpan(20), pseudo));
    }
}
