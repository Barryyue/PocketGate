using System.Buffers.Binary;
using System.Net;
using NetworkControl.Core.Domain;

namespace NetworkControl.Engine.Linux;

internal readonly record struct ParsedNfqueuePacket(
    Direction Direction,
    L4Protocol Protocol,
    IPEndPoint Local,
    IPEndPoint Remote);

/// <summary>從 NFQUEUE 複製的 IP/L4 頭解析判決所需的五元組。解析失敗由調用方按失效策略處理，不能默認放行。</summary>
internal static class NfqueuePacketParser
{
    internal const byte LocalInHook = 1;   // NF_INET_LOCAL_IN
    internal const byte LocalOutHook = 3;  // NF_INET_LOCAL_OUT

    private const int MaxExtensionHeaders = 8;

    public static bool TryParse(ReadOnlySpan<byte> packet, byte hook, out ParsedNfqueuePacket result)
    {
        result = default;
        Direction direction;
        switch (hook)
        {
            case LocalOutHook:
                direction = Direction.Outbound;
                break;
            case LocalInHook:
                direction = Direction.Inbound;
                break;
            default:
                return false;
        }

        if (packet.IsEmpty)
        {
            return false;
        }

        return (packet[0] >> 4) switch
        {
            4 => TryParseV4(packet, direction, out result),
            6 => TryParseV6(packet, direction, out result),
            _ => false,
        };
    }

    private static bool TryParseV4(ReadOnlySpan<byte> packet, Direction direction, out ParsedNfqueuePacket result)
    {
        result = default;
        if (packet.Length < 20)
        {
            return false;
        }

        var headerLength = (packet[0] & 0x0F) * 4;
        if (headerLength < 20 || packet.Length < headerLength)
        {
            return false;
        }

        // 非首個分片沒有 L4 頭（nftables 的連接跟蹤通常已重組，這裡防禦性處理）
        if ((BinaryPrimitives.ReadUInt16BigEndian(packet[6..]) & 0x1FFF) != 0)
        {
            return false;
        }

        return ToProtocol(packet[9]) is { } protocol
            && TryCreateResult(packet[12..16], packet[16..20], packet[headerLength..], direction, protocol, out result);
    }

    private static bool TryParseV6(ReadOnlySpan<byte> packet, Direction direction, out ParsedNfqueuePacket result)
    {
        result = default;
        if (packet.Length < 40)
        {
            return false;
        }

        var next = packet[6];
        var offset = 40;
        for (var i = 0; i <= MaxExtensionHeaders; i++)
        {
            if (ToProtocol(next) is { } protocol)
            {
                return TryCreateResult(packet[8..24], packet[24..40], packet[offset..], direction, protocol, out result);
            }

            if (packet.Length < offset + 8)
            {
                return false;
            }

            var extension = packet[offset..];
            int length;
            switch (next)
            {
                case 0:   // Hop-by-Hop
                case 43:  // Routing
                case 60:  // Destination Options
                    length = (extension[1] + 1) * 8;
                    break;
                case 44:  // Fragment
                    if ((BinaryPrimitives.ReadUInt16BigEndian(extension[2..]) & 0xFFF8) != 0)
                    {
                        return false; // 非首個分片
                    }

                    length = 8;
                    break;
                case 51:  // Authentication Header
                    length = (extension[1] + 2) * 4;
                    break;
                default:  // ESP（50）、No Next Header（59）等：無法得到端口
                    return false;
            }

            next = extension[0];
            offset += length;
            if (offset > packet.Length)
            {
                return false;
            }
        }

        return false;
    }

    private static bool TryCreateResult(
        ReadOnlySpan<byte> source,
        ReadOnlySpan<byte> destination,
        ReadOnlySpan<byte> transport,
        Direction direction,
        L4Protocol protocol,
        out ParsedNfqueuePacket result)
    {
        result = default;
        if (transport.Length < 4)
        {
            return false;
        }

        var sourceEndPoint = new IPEndPoint(new IPAddress(source), BinaryPrimitives.ReadUInt16BigEndian(transport));
        var destinationEndPoint = new IPEndPoint(new IPAddress(destination), BinaryPrimitives.ReadUInt16BigEndian(transport[2..]));
        result = direction == Direction.Outbound
            ? new ParsedNfqueuePacket(direction, protocol, sourceEndPoint, destinationEndPoint)
            : new ParsedNfqueuePacket(direction, protocol, destinationEndPoint, sourceEndPoint);
        return true;
    }

    private static L4Protocol? ToProtocol(byte value) => value switch
    {
        6 => L4Protocol.Tcp,
        17 => L4Protocol.Udp,
        _ => null,
    };
}
