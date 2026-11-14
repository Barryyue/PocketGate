using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;

namespace NetworkControl.Engine.Vpn.Packets;

/// <summary>構造寫回虛擬網卡的報文（目前只有假 DNS 的 IPv4 / UDP 回應）。</summary>
public static class IpPacketBuilder
{
    private const int Ipv4Header = 20;
    private const int UdpHeader = 8;

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
        span[9] = 17;
        source.Address.TryWriteBytes(span[12..], out _);
        destination.Address.TryWriteBytes(span[16..], out _);
        BinaryPrimitives.WriteUInt16BigEndian(span[10..], Checksum(span[..Ipv4Header], 0));

        var udp = span[Ipv4Header..];
        BinaryPrimitives.WriteUInt16BigEndian(udp, (ushort)source.Port);
        BinaryPrimitives.WriteUInt16BigEndian(udp[2..], (ushort)destination.Port);
        BinaryPrimitives.WriteUInt16BigEndian(udp[4..], (ushort)udpLength);
        payload.CopyTo(udp[UdpHeader..]);

        // 偽首部：源地址、目標地址、協議、UDP 長度
        uint pseudo = Sum(span.Slice(12, 8)) + 17u + (uint)udpLength;
        var checksum = Checksum(udp[..udpLength], pseudo);
        BinaryPrimitives.WriteUInt16BigEndian(udp[6..], checksum == 0 ? (ushort)0xFFFF : checksum);
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
