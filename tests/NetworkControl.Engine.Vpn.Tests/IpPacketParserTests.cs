using System.Net;
using NetworkControl.Core.Domain;
using NetworkControl.Engine.Vpn.Packets;
using static NetworkControl.Engine.Vpn.Tests.Packets;

namespace NetworkControl.Engine.Vpn.Tests;

public class IpPacketParserTests
{
    [Fact]
    public void Parses_ipv4_tcp_syn()
    {
        var packet = Syn("10.111.222.1", 40_000, "140.82.112.3", 443);

        Assert.True(IpPacketParser.TryParse(packet, out var info));
        Assert.Equal(L4Protocol.Tcp, info.Protocol);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("10.111.222.1"), 40_000), info.Source);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("140.82.112.3"), 443), info.Destination);
        Assert.Equal(TcpFlags.Syn, info.TcpFlags);
        Assert.True(info.IsConnectionAttempt);
    }

    [Theory]
    [InlineData(0x12)] // SYN + ACK
    [InlineData(0x10)] // ACK
    [InlineData(0x18)] // PSH + ACK
    [InlineData(0x04)] // RST
    public void Tcp_without_pure_syn_is_not_an_attempt(byte flags)
    {
        var packet = Ipv4("10.111.222.1", "1.1.1.1", Tcp, TcpSegment(40_000, 443, flags));
        Assert.True(IpPacketParser.TryParse(packet, out var info));
        Assert.False(info.IsConnectionAttempt);
    }

    [Fact]
    public void Parses_ipv4_udp()
    {
        var packet = Ipv4("10.111.222.1", "8.8.8.8", Udp, UdpDatagram(53_000, 53));

        Assert.True(IpPacketParser.TryParse(packet, out var info));
        Assert.Equal(L4Protocol.Udp, info.Protocol);
        Assert.Equal(53, info.Destination.Port);
        Assert.True(info.IsConnectionAttempt);
    }

    [Fact]
    public void Icmp_is_parsed_but_not_an_attempt()
    {
        var packet = Ipv4("10.111.222.1", "8.8.8.8", Icmp, new byte[8]);

        Assert.True(IpPacketParser.TryParse(packet, out var info));
        Assert.Equal(L4Protocol.Icmp, info.Protocol);
        Assert.False(info.IsConnectionAttempt);
    }

    [Fact]
    public void Parses_ipv6_tcp_syn()
    {
        var packet = Syn("fd00:6e63::1", 41_000, "2606:4700::1111", 443);

        Assert.True(IpPacketParser.TryParse(packet, out var info));
        Assert.Equal(IPAddress.Parse("2606:4700::1111"), info.Destination.Address);
        Assert.Equal(443, info.Destination.Port);
        Assert.True(info.IsConnectionAttempt);
    }

    [Fact]
    public void Skips_ipv6_extension_headers()
    {
        var hopByHop = (Type: (byte)0, Body: new byte[8]);
        var destinationOptions = (Type: (byte)60, Body: new byte[16]);
        destinationOptions.Body[1] = 1; // (1 + 1) * 8 = 16 字節
        var packet = Ipv6("fd00:6e63::1", "2001:db8::1", Udp, UdpDatagram(5000, 443), hopByHop, destinationOptions);

        Assert.True(IpPacketParser.TryParse(packet, out var info));
        Assert.Equal(L4Protocol.Udp, info.Protocol);
        Assert.Equal(443, info.Destination.Port);
    }

    [Fact]
    public void Parses_first_ipv6_fragment_and_rejects_later_fragments()
    {
        var first = (Type: (byte)44, Body: new byte[8]);
        Assert.True(IpPacketParser.TryParse(Ipv6("fd00:6e63::1", "2001:db8::1", Udp, UdpDatagram(5000, 443), first), out _));

        var later = (Type: (byte)44, Body: new byte[8]);
        later.Body[2] = 0x01; // 片偏移 32 × 8 字節
        Assert.False(IpPacketParser.TryParse(Ipv6("fd00:6e63::1", "2001:db8::1", Udp, UdpDatagram(5000, 443), later), out _));
    }

    [Fact]
    public void Rejects_non_first_ipv4_fragment()
    {
        var packet = Ipv4("10.111.222.1", "1.1.1.1", Udp, UdpDatagram(5000, 443), fragmentField: 0x0010);
        Assert.False(IpPacketParser.TryParse(packet, out _));
    }

    [Fact]
    public void Accepts_first_ipv4_fragment_with_more_fragments_flag()
    {
        var packet = Ipv4("10.111.222.1", "1.1.1.1", Udp, UdpDatagram(5000, 443), fragmentField: 0x2000);
        Assert.True(IpPacketParser.TryParse(packet, out _));
    }

    [Fact]
    public void Rejects_truncated_and_malformed_packets()
    {
        var syn = Syn("10.111.222.1", 40_000, "1.1.1.1", 443);

        Assert.False(IpPacketParser.TryParse([], out _));
        Assert.False(IpPacketParser.TryParse(syn.AsSpan(0, 19), out _));
        Assert.False(IpPacketParser.TryParse(syn.AsSpan(0, 30), out _)); // 總長度字段大於實際長度

        var badIhl = (byte[])syn.Clone();
        badIhl[0] = 0x44; // IHL = 16 字節
        Assert.False(IpPacketParser.TryParse(badIhl, out _));

        var unknownVersion = (byte[])syn.Clone();
        unknownVersion[0] = 0x55;
        Assert.False(IpPacketParser.TryParse(unknownVersion, out _));

        // TCP 頭不完整
        Assert.False(IpPacketParser.TryParse(Ipv4("10.111.222.1", "1.1.1.1", Tcp, new byte[10]), out _));
    }
}
