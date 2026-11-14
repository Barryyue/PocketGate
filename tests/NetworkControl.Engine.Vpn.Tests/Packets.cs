using System.Buffers.Binary;
using System.Net;

namespace NetworkControl.Engine.Vpn.Tests;

/// <summary>構造測試用的 IP 報文。</summary>
internal static class Packets
{
    public const byte Tcp = 6;
    public const byte Udp = 17;
    public const byte Icmp = 1;

    public static byte[] Ipv4(string source, string destination, byte protocol, byte[] transport, ushort fragmentField = 0)
    {
        var packet = new byte[20 + transport.Length];
        packet[0] = 0x45;
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(2), (ushort)packet.Length);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(6), fragmentField);
        packet[8] = 64;
        packet[9] = protocol;
        IPAddress.Parse(source).GetAddressBytes().CopyTo(packet, 12);
        IPAddress.Parse(destination).GetAddressBytes().CopyTo(packet, 16);
        transport.CopyTo(packet, 20);
        return packet;
    }

    /// <summary>IPv6 報文；<paramref name="extensions"/> 為按順序排列的擴展頭（next header, 內容），內容長度必須是 8 的倍數。</summary>
    public static byte[] Ipv6(string source, string destination, byte protocol, byte[] transport, params (byte Type, byte[] Body)[] extensions)
    {
        var extLength = extensions.Sum(e => e.Body.Length);
        var packet = new byte[40 + extLength + transport.Length];
        packet[0] = 0x60;
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(4), (ushort)(extLength + transport.Length));
        packet[6] = extensions.Length > 0 ? extensions[0].Type : protocol;
        packet[7] = 64;
        IPAddress.Parse(source).GetAddressBytes().CopyTo(packet, 8);
        IPAddress.Parse(destination).GetAddressBytes().CopyTo(packet, 24);

        var offset = 40;
        for (var i = 0; i < extensions.Length; i++)
        {
            var body = extensions[i].Body;
            body.CopyTo(packet, offset);
            packet[offset] = i + 1 < extensions.Length ? extensions[i + 1].Type : protocol;
            offset += body.Length;
        }

        transport.CopyTo(packet, offset);
        return packet;
    }

    public static byte[] TcpSegment(ushort sourcePort, ushort destinationPort, byte flags)
    {
        var segment = new byte[20];
        BinaryPrimitives.WriteUInt16BigEndian(segment, sourcePort);
        BinaryPrimitives.WriteUInt16BigEndian(segment.AsSpan(2), destinationPort);
        segment[12] = 0x50;
        segment[13] = flags;
        return segment;
    }

    public static byte[] UdpDatagram(ushort sourcePort, ushort destinationPort)
    {
        var datagram = new byte[12];
        BinaryPrimitives.WriteUInt16BigEndian(datagram, sourcePort);
        BinaryPrimitives.WriteUInt16BigEndian(datagram.AsSpan(2), destinationPort);
        BinaryPrimitives.WriteUInt16BigEndian(datagram.AsSpan(4), (ushort)datagram.Length);
        return datagram;
    }

    public static byte[] Syn(string source, ushort sourcePort, string destination, ushort destinationPort) =>
        source.Contains(':', StringComparison.Ordinal)
            ? Ipv6(source, destination, Tcp, TcpSegment(sourcePort, destinationPort, 0x02))
            : Ipv4(source, destination, Tcp, TcpSegment(sourcePort, destinationPort, 0x02));
}
