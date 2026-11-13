using System.Buffers.Binary;
using System.Net;
using NetworkControl.Core.Domain;

namespace NetworkControl.Engine.Vpn.Packets;

/// <summary>TCP 標誌位。</summary>
[Flags]
public enum TcpFlags : byte
{
    None = 0,
    Fin = 0x01,
    Syn = 0x02,
    Rst = 0x04,
    Psh = 0x08,
    Ack = 0x10,
}

/// <summary>從 IP 報文中解析出的連接信息。</summary>
public readonly record struct PacketInfo(
    L4Protocol Protocol,
    IPEndPoint Source,
    IPEndPoint Destination,
    TcpFlags TcpFlags)
{
    /// <summary>
    /// 是否表示一次新的聯網嘗試：TCP 只看 SYN（不帶 ACK），UDP 每個報文都可能是新的流（由上報器按五元組去重）。
    /// </summary>
    public bool IsConnectionAttempt => Protocol switch
    {
        L4Protocol.Tcp => (TcpFlags & (TcpFlags.Syn | TcpFlags.Ack)) == TcpFlags.Syn,
        L4Protocol.Udp => true,
        _ => false,
    };
}

/// <summary>
/// IPv4 / IPv6 報文頭解析（只讀取判斷連接歸屬所需的字段）。
/// 非首個分片、截斷、長度字段不合法的報文返回 false，調用方直接丟棄。
/// </summary>
public static class IpPacketParser
{
    private const int Ipv4MinHeader = 20;
    private const int Ipv6Header = 40;
    private const int MaxIpv6ExtensionHeaders = 8;

    private const byte NextHeaderHopByHop = 0;
    private const byte NextHeaderRouting = 43;
    private const byte NextHeaderFragment = 44;
    private const byte NextHeaderAuthentication = 51;
    private const byte NextHeaderDestinationOptions = 60;

    public static bool TryParse(ReadOnlySpan<byte> packet, out PacketInfo info)
    {
        info = default;
        if (packet.IsEmpty)
        {
            return false;
        }

        return (packet[0] >> 4) switch
        {
            4 => TryParseIpv4(packet, out info),
            6 => TryParseIpv6(packet, out info),
            _ => false,
        };
    }

    private static bool TryParseIpv4(ReadOnlySpan<byte> packet, out PacketInfo info)
    {
        info = default;
        if (packet.Length < Ipv4MinHeader)
        {
            return false;
        }

        var headerLength = (packet[0] & 0x0F) * 4;
        var totalLength = BinaryPrimitives.ReadUInt16BigEndian(packet[2..]);
        if (headerLength < Ipv4MinHeader || totalLength < headerLength || totalLength > packet.Length)
        {
            return false;
        }

        // 非首個分片不帶傳輸層頭
        var fragmentOffset = BinaryPrimitives.ReadUInt16BigEndian(packet[6..]) & 0x1FFF;
        if (fragmentOffset != 0)
        {
            return false;
        }

        var source = new IPAddress(packet.Slice(12, 4));
        var destination = new IPAddress(packet.Slice(16, 4));
        return TryParseTransport(packet[9], packet[headerLength..totalLength], source, destination, out info);
    }

    private static bool TryParseIpv6(ReadOnlySpan<byte> packet, out PacketInfo info)
    {
        info = default;
        if (packet.Length < Ipv6Header)
        {
            return false;
        }

        var payloadLength = BinaryPrimitives.ReadUInt16BigEndian(packet[4..]);
        if (Ipv6Header + payloadLength > packet.Length)
        {
            return false;
        }

        var source = new IPAddress(packet.Slice(8, 16));
        var destination = new IPAddress(packet.Slice(24, 16));
        var nextHeader = packet[6];
        var payload = packet.Slice(Ipv6Header, payloadLength);

        for (var i = 0; i < MaxIpv6ExtensionHeaders; i++)
        {
            int length;
            switch (nextHeader)
            {
                case NextHeaderHopByHop or NextHeaderRouting or NextHeaderDestinationOptions:
                    if (payload.Length < 8)
                    {
                        return false;
                    }

                    length = (payload[1] + 1) * 8;
                    break;
                case NextHeaderFragment:
                    if (payload.Length < 8)
                    {
                        return false;
                    }

                    if ((BinaryPrimitives.ReadUInt16BigEndian(payload[2..]) >> 3) != 0)
                    {
                        return false;
                    }

                    length = 8;
                    break;
                case NextHeaderAuthentication:
                    if (payload.Length < 8)
                    {
                        return false;
                    }

                    length = (payload[1] + 2) * 4;
                    break;
                default:
                    return TryParseTransport(nextHeader, payload, source, destination, out info);
            }

            if (length > payload.Length)
            {
                return false;
            }

            nextHeader = payload[0];
            payload = payload[length..];
        }

        info = default;
        return false;
    }

    private static bool TryParseTransport(byte protocolNumber, ReadOnlySpan<byte> segment, IPAddress source, IPAddress destination, out PacketInfo info)
    {
        info = default;
        var protocol = protocolNumber switch
        {
            6 => L4Protocol.Tcp,
            17 => L4Protocol.Udp,
            1 => L4Protocol.Icmp,
            58 => L4Protocol.IcmpV6,
            _ => L4Protocol.Other,
        };

        ushort sourcePort = 0, destinationPort = 0;
        var flags = TcpFlags.None;
        switch (protocol)
        {
            case L4Protocol.Tcp:
                if (segment.Length < 20)
                {
                    return false;
                }

                sourcePort = BinaryPrimitives.ReadUInt16BigEndian(segment);
                destinationPort = BinaryPrimitives.ReadUInt16BigEndian(segment[2..]);
                flags = (TcpFlags)(segment[13] & 0x1F);
                break;
            case L4Protocol.Udp:
                if (segment.Length < 8)
                {
                    return false;
                }

                sourcePort = BinaryPrimitives.ReadUInt16BigEndian(segment);
                destinationPort = BinaryPrimitives.ReadUInt16BigEndian(segment[2..]);
                break;
        }

        info = new PacketInfo(protocol, new IPEndPoint(source, sourcePort), new IPEndPoint(destination, destinationPort), flags);
        return true;
    }
}
