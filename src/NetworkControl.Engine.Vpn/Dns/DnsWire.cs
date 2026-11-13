using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace NetworkControl.Engine.Vpn.Dns;

/// <summary>DNS 查詢的問題部分。</summary>
/// <param name="Id">事務 ID。</param>
/// <param name="RecursionDesired">RD 標誌。</param>
/// <param name="Name">查詢的域名（小寫，不帶結尾的點）。</param>
/// <param name="Type">查詢類型（1 = A，28 = AAAA，65 = HTTPS）。</param>
/// <param name="QuestionLength">問題部分在報文中的字節數（回應時原樣回顯）。</param>
public readonly record struct DnsQuery(ushort Id, bool RecursionDesired, string Name, ushort Type, int QuestionLength);

/// <summary>
/// 最小的 DNS 報文處理（RFC 1035）：只解析標準查詢的第一個問題，並構造帶一條 A 記錄或沒有記錄（NODATA）的回應。
/// 用於假 DNS（docs/android.md §3.2）：讓被阻斷的應用解析成功後發起連接，從而能按 UID 歸屬並詢問。
/// </summary>
public static class DnsWire
{
    public const ushort TypeA = 1;
    public const ushort TypeAaaa = 28;
    public const ushort ClassIn = 1;

    private const int HeaderLength = 12;
    private const int MaxNameLength = 253;

    public static bool TryParseQuery(ReadOnlySpan<byte> message, out DnsQuery query)
    {
        query = default;
        if (message.Length < HeaderLength)
        {
            return false;
        }

        var flags = BinaryPrimitives.ReadUInt16BigEndian(message[2..]);
        var isResponse = (flags & 0x8000) != 0;
        var opcode = (flags >> 11) & 0x0F;
        var questions = BinaryPrimitives.ReadUInt16BigEndian(message[4..]);
        if (isResponse || opcode != 0 || questions == 0)
        {
            return false;
        }

        var offset = HeaderLength;
        var name = new StringBuilder();
        while (true)
        {
            if (offset >= message.Length)
            {
                return false;
            }

            var length = message[offset++];
            if (length == 0)
            {
                break;
            }

            // 查詢中不應出現壓縮指針；標籤長度上限 63
            if (length > 63 || offset + length > message.Length || name.Length + length + 1 > MaxNameLength)
            {
                return false;
            }

            if (name.Length > 0)
            {
                name.Append('.');
            }

            foreach (var b in message.Slice(offset, length))
            {
                name.Append(b is >= (byte)'A' and <= (byte)'Z' ? (char)(b + 32) : (char)b);
            }

            offset += length;
        }

        if (offset + 4 > message.Length)
        {
            return false;
        }

        var type = BinaryPrimitives.ReadUInt16BigEndian(message[offset..]);
        var questionClass = BinaryPrimitives.ReadUInt16BigEndian(message[(offset + 2)..]);
        if (questionClass != ClassIn)
        {
            return false;
        }

        offset += 4;
        query = new DnsQuery(
            BinaryPrimitives.ReadUInt16BigEndian(message),
            (flags & 0x0100) != 0,
            name.ToString(),
            type,
            offset - HeaderLength);
        return true;
    }

    /// <summary>
    /// 構造回應：<paramref name="address"/> 有值時帶一條 A 記錄，否則為 NODATA（NOERROR、沒有答案）。
    /// <paramref name="question"/> 是原查詢報文中的問題部分。
    /// </summary>
    public static byte[] BuildResponse(DnsQuery query, ReadOnlySpan<byte> question, IPAddress? address, uint ttlSeconds)
    {
        if (address is not null && address.AddressFamily != AddressFamily.InterNetwork)
        {
            throw new ArgumentException("只支持 IPv4 地址。", nameof(address));
        }

        var answerLength = address is null ? 0 : 16;
        var response = new byte[HeaderLength + question.Length + answerLength];
        var span = response.AsSpan();

        BinaryPrimitives.WriteUInt16BigEndian(span, query.Id);
        // QR=1、RA=1、RCODE=0，RD 從查詢複製
        BinaryPrimitives.WriteUInt16BigEndian(span[2..], (ushort)(0x8080 | (query.RecursionDesired ? 0x0100 : 0)));
        BinaryPrimitives.WriteUInt16BigEndian(span[4..], 1);
        BinaryPrimitives.WriteUInt16BigEndian(span[6..], (ushort)(address is null ? 0 : 1));
        question.CopyTo(span[HeaderLength..]);

        if (address is not null)
        {
            var answer = span[(HeaderLength + question.Length)..];
            BinaryPrimitives.WriteUInt16BigEndian(answer, 0xC00C); // 指向問題中的域名
            BinaryPrimitives.WriteUInt16BigEndian(answer[2..], TypeA);
            BinaryPrimitives.WriteUInt16BigEndian(answer[4..], ClassIn);
            BinaryPrimitives.WriteUInt32BigEndian(answer[6..], ttlSeconds);
            BinaryPrimitives.WriteUInt16BigEndian(answer[10..], 4);
            address.TryWriteBytes(answer[12..], out _);
        }

        return response;
    }
}
