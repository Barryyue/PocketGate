using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;

namespace NetworkControl.Engine.Vpn.Packets;

/// <summary>
/// 構造寫回虛擬網卡的報文：假 DNS 的 IPv4 / UDP 回應、TCP RST（快速失敗）、ICMP 端口不可達（UDP 快速失敗）。
/// </summary>
public static class IpPacketBuilder
{
    private const int Ipv4Header = 20;
    private const int Ipv6Header = 40;
    private const int UdpHeader = 8;
    private const int TcpHeader = 20;
    private const int IcmpHeader = 8;

    private const byte ProtocolIcmpV4 = 1;
    private const byte ProtocolTcp = 6;
    private const byte ProtocolUdp = 17;
    private const byte ProtocolIcmpV6 = 58;

    /// <summary>ICMPv4 錯誤引用的原始報文：IP 頭 + 前 8 字節傳輸層數據。</summary>
    private const int Icmpv4QuoteBytes = 8;

    /// <summary>IPv6 報文（含 ICMPv6 錯誤）不超過 1280 字節（RFC 4443 §2.4）。</summary>
    private const int MaxIpv6Packet = 1280;

    /// <summary>構造 IPv4 / UDP 報文，IP 頭和 UDP 校驗和都會計算。</summary>
    public static byte[] Udp4(IPEndPoint source, IPEndPoint destination, ReadOnlySpan<byte> payload)
    {
        if (source.AddressFamily != AddressFamily.InterNetwork || destination.AddressFamily != AddressFamily.InterNetwork)
        {
            throw new ArgumentException("只支持 IPv4。");
        }

        var udpLength = UdpHeader + payload.Length;
        var totalLength = Ipv4Header + udpLength;
        if (totalLength > ushort.MaxValue)
        {
            throw new ArgumentException("載荷過長。", nameof(payload));
        }

        var packet = new byte[totalLength];
        var span = packet.AsSpan();

        span[0] = 0x45;
        BinaryPrimitives.WriteUInt16BigEndian(span[2..], (ushort)totalLength);
        BinaryPrimitives.WriteUInt16BigEndian(span[6..], 0x4000); // DF
        span[8] = 64;
        span[9] = ProtocolUdp;
        source.Address.TryWriteBytes(span[12..], out _);
        destination.Address.TryWriteBytes(span[16..], out _);
        BinaryPrimitives.WriteUInt16BigEndian(span[10..], Checksum(span[..Ipv4Header], 0));

        var udp = span[Ipv4Header..];
        BinaryPrimitives.WriteUInt16BigEndian(udp, (ushort)source.Port);
        BinaryPrimitives.WriteUInt16BigEndian(udp[2..], (ushort)destination.Port);
        BinaryPrimitives.WriteUInt16BigEndian(udp[4..], (ushort)udpLength);
        payload.CopyTo(udp[UdpHeader..]);

        var checksum = TransportChecksum(span.Slice(12, 8), ProtocolUdp, udp[..udpLength]);
        BinaryPrimitives.WriteUInt16BigEndian(udp[6..], checksum == 0 ? (ushort)0xFFFF : checksum);
        return packet;
    }

    /// <summary>
    /// 構造回給應用（源 / 目的互換）的 TCP RST+ACK，seq = 0，ack = 收到的 seq + 1，窗口 0，無選項。
    /// 按地址族選擇 IPv4 / IPv6，校驗和（含偽首部）都會計算。
    /// </summary>
    public static byte[] TcpReset(IPEndPoint source, IPEndPoint destination, uint acknowledgment) =>
        source.AddressFamily switch
        {
            AddressFamily.InterNetwork => TcpReset4(source, destination, acknowledgment),
            AddressFamily.InterNetworkV6 => TcpReset6(source, destination, acknowledgment),
            _ => throw new ArgumentException("只支持 IPv4 和 IPv6。", nameof(source)),
        };

    /// <summary>IPv4 的 TCP RST+ACK。</summary>
    public static byte[] TcpReset4(IPEndPoint source, IPEndPoint destination, uint acknowledgment)
    {
        if (source.AddressFamily != AddressFamily.InterNetwork || destination.AddressFamily != AddressFamily.InterNetwork)
        {
            throw new ArgumentException("只支持 IPv4。");
        }

        var packet = new byte[Ipv4Header + TcpHeader];
        var span = packet.AsSpan();

        span[0] = 0x45;
        BinaryPrimitives.WriteUInt16BigEndian(span[2..], (ushort)packet.Length);
        BinaryPrimitives.WriteUInt16BigEndian(span[6..], 0x4000); // DF
        span[8] = 64;
        span[9] = ProtocolTcp;
        source.Address.TryWriteBytes(span[12..], out _);
        destination.Address.TryWriteBytes(span[16..], out _);
        BinaryPrimitives.WriteUInt16BigEndian(span[10..], Checksum(span[..Ipv4Header], 0));

        var tcp = span[Ipv4Header..];
        WriteTcpReset(tcp, source.Port, destination.Port, acknowledgment);
        var checksum = TransportChecksum(span.Slice(12, 8), ProtocolTcp, tcp);
        BinaryPrimitives.WriteUInt16BigEndian(tcp[16..], checksum);
        return packet;
    }

    /// <summary>IPv6 的 TCP RST+ACK（校驗和包含 IPv6 偽首部）。</summary>
    public static byte[] TcpReset6(IPEndPoint source, IPEndPoint destination, uint acknowledgment)
    {
        if (source.AddressFamily != AddressFamily.InterNetworkV6 || destination.AddressFamily != AddressFamily.InterNetworkV6)
        {
            throw new ArgumentException("只支持 IPv6。");
        }

        var packet = new byte[Ipv6Header + TcpHeader];
        var span = packet.AsSpan();

        span[0] = 0x60;
        BinaryPrimitives.WriteUInt16BigEndian(span[4..], TcpHeader);
        span[6] = ProtocolTcp;
        span[7] = 64;
        source.Address.TryWriteBytes(span[8..], out _);
        destination.Address.TryWriteBytes(span[24..], out _);

        var tcp = span[Ipv6Header..];
        WriteTcpReset(tcp, source.Port, destination.Port, acknowledgment);
        var checksum = TransportChecksum(span.Slice(8, 32), ProtocolTcp, tcp);
        BinaryPrimitives.WriteUInt16BigEndian(tcp[16..], checksum);
        return packet;
    }

    /// <summary>
    /// 構造回給應用（源 / 目的互換）的 ICMPv4 type 3 code 3（端口不可達）或 ICMPv6 type 1 code 4，
    /// 載荷引用原始報文。按地址族選擇，校驗和（含 IPv6 偽首部）都會計算。
    /// </summary>
    public static byte[] IcmpPortUnreachable(IPEndPoint source, IPEndPoint destination, ReadOnlySpan<byte> original) =>
        source.AddressFamily switch
        {
            AddressFamily.InterNetwork => IcmpUnreachable4(source, destination, original),
            AddressFamily.InterNetworkV6 => IcmpUnreachable6(source, destination, original),
            _ => throw new ArgumentException("只支持 IPv4 和 IPv6。", nameof(source)),
        };

    /// <summary>ICMPv4 端口不可達（type 3 code 3）：原始 IP 頭 + 前 8 字節傳輸層數據。</summary>
    public static byte[] IcmpUnreachable4(IPEndPoint source, IPEndPoint destination, ReadOnlySpan<byte> original)
    {
        if (source.AddressFamily != AddressFamily.InterNetwork || destination.AddressFamily != AddressFamily.InterNetwork)
        {
            throw new ArgumentException("只支持 IPv4。");
        }

        var originalHeader = original.Length >= Ipv4Header ? (original[0] & 0x0F) * 4 : Ipv4Header;
        var quoteLength = Math.Min(original.Length, Math.Max(originalHeader, Ipv4Header) + Icmpv4QuoteBytes);
        var icmpLength = IcmpHeader + quoteLength;
        var packet = new byte[Ipv4Header + icmpLength];
        var span = packet.AsSpan();

        span[0] = 0x45;
        BinaryPrimitives.WriteUInt16BigEndian(span[2..], (ushort)packet.Length);
        BinaryPrimitives.WriteUInt16BigEndian(span[6..], 0x4000); // DF
        span[8] = 64;
        span[9] = ProtocolIcmpV4;
        source.Address.TryWriteBytes(span[12..], out _);
        destination.Address.TryWriteBytes(span[16..], out _);
        BinaryPrimitives.WriteUInt16BigEndian(span[10..], Checksum(span[..Ipv4Header], 0));

        var icmp = span[Ipv4Header..];
        icmp[0] = 3;
        icmp[1] = 3;
        original[..quoteLength].CopyTo(icmp[IcmpHeader..]);
        BinaryPrimitives.WriteUInt16BigEndian(icmp[2..], Checksum(icmp[..icmpLength], 0));
        return packet;
    }

    /// <summary>ICMPv6 端口不可達（type 1 code 4）：引用盡量多的原始報文，總長不超過 1280。</summary>
    public static byte[] IcmpUnreachable6(IPEndPoint source, IPEndPoint destination, ReadOnlySpan<byte> original)
    {
        if (source.AddressFamily != AddressFamily.InterNetworkV6 || destination.AddressFamily != AddressFamily.InterNetworkV6)
        {
            throw new ArgumentException("只支持 IPv6。");
        }

        var quoteLength = Math.Min(original.Length, MaxIpv6Packet - Ipv6Header - IcmpHeader);
        var icmpLength = IcmpHeader + quoteLength;
        var packet = new byte[Ipv6Header + icmpLength];
        var span = packet.AsSpan();

        span[0] = 0x60;
        BinaryPrimitives.WriteUInt16BigEndian(span[4..], (ushort)icmpLength);
        span[6] = ProtocolIcmpV6;
        span[7] = 64;
        source.Address.TryWriteBytes(span[8..], out _);
        destination.Address.TryWriteBytes(span[24..], out _);

        var icmp = span[Ipv6Header..];
        icmp[0] = 1;
        icmp[1] = 4;
        original[..quoteLength].CopyTo(icmp[IcmpHeader..]);
        var checksum = TransportChecksum(span.Slice(8, 32), ProtocolIcmpV6, icmp);
        BinaryPrimitives.WriteUInt16BigEndian(icmp[2..], checksum);
        return packet;
    }

    /// <summary>互聯網校驗和（RFC 1071）。</summary>
    public static ushort Checksum(ReadOnlySpan<byte> data, uint initial)
    {
        var sum = initial + Sum(data);
        while ((sum >> 16) != 0)
        {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }

        return (ushort)~sum;
    }

    private static void WriteTcpReset(Span<byte> tcp, int sourcePort, int destinationPort, uint acknowledgment)
    {
        BinaryPrimitives.WriteUInt16BigEndian(tcp, (ushort)sourcePort);
        BinaryPrimitives.WriteUInt16BigEndian(tcp[2..], (ushort)destinationPort);
        BinaryPrimitives.WriteUInt32BigEndian(tcp[4..], 0); // seq
        BinaryPrimitives.WriteUInt32BigEndian(tcp[8..], acknowledgment);
        tcp[12] = 0x50; // 數據偏移 5（20 字節），無選項
        tcp[13] = (byte)(TcpFlags.Rst | TcpFlags.Ack);
        BinaryPrimitives.WriteUInt16BigEndian(tcp[14..], 0); // 窗口 0
    }

    /// <summary>傳輸層校驗和：偽首部為地址、協議號和段長度（IPv4 和 IPv6 的求和形式相同）。</summary>
    private static ushort TransportChecksum(ReadOnlySpan<byte> addresses, byte protocol, ReadOnlySpan<byte> segment) =>
        Checksum(segment, Sum(addresses) + protocol + (uint)segment.Length);

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
