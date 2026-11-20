using System.Buffers.Binary;
using System.Text;

namespace NetworkControl.Engine.Vpn.Forwarding;

/// <summary>
/// 從 TCP 首包提取 TLS SNI 或 HTTP Host 標頭，供逐連線網域攔截清單與 DoH 防繞過使用。
/// </summary>
public static class DomainSniffer
{
    public static bool TryExtractDomain(ReadOnlySpan<byte> tcpPayload, out string domain)
    {
        if (TryExtractTlsSni(tcpPayload, out domain)) return true;
        if (TryExtractHttpHost(tcpPayload, out domain)) return true;
        domain = string.Empty;
        return false;
    }

    public static bool TryExtractTlsSni(ReadOnlySpan<byte> data, out string sni)
    {
        sni = string.Empty;
        // TLS Record: ContentType=22 (Handshake), Version=0x03xx
        if (data.Length < 43 || data[0] != 22 || data[1] != 3) return false;
        var pos = 5;
        if (pos + 4 > data.Length || data[pos] != 1) return false; // ClientHello
        pos += 4 + 2 + 32; // handshake header + version + random
        if (pos >= data.Length) return false;
        var sessionIdLen = data[pos++];
        pos += sessionIdLen;
        if (pos + 2 > data.Length) return false;
        var cipherLen = BinaryPrimitives.ReadUInt16BigEndian(data[pos..]);
        pos += 2 + cipherLen;
        if (pos >= data.Length) return false;
        var compLen = data[pos++];
        pos += compLen;
        if (pos + 2 > data.Length) return false;
        var extTotalLen = BinaryPrimitives.ReadUInt16BigEndian(data[pos..]);
        pos += 2;
        var extEnd = Math.Min(data.Length, pos + extTotalLen);
        while (pos + 4 <= extEnd)
        {
            var extType = BinaryPrimitives.ReadUInt16BigEndian(data[pos..]);
            var extLen = BinaryPrimitives.ReadUInt16BigEndian(data[(pos + 2)..]);
            pos += 4;
            if (pos + extLen > extEnd) break;
            if (extType == 0 && extLen >= 5) // server_name
            {
                var nameListLen = BinaryPrimitives.ReadUInt16BigEndian(data[pos..]);
                if (pos + 2 + nameListLen <= extEnd && data[pos + 2] == 0)
                {
                    var nameLen = BinaryPrimitives.ReadUInt16BigEndian(data[(pos + 3)..]);
                    if (pos + 5 + nameLen <= extEnd)
                    {
                        sni = Encoding.ASCII.GetString(data.Slice(pos + 5, nameLen)).Trim();
                        return sni.Length > 0;
                    }
                }
            }

            pos += extLen;
        }

        return false;
    }

    public static bool TryExtractHttpHost(ReadOnlySpan<byte> data, out string host)
    {
        host = string.Empty;
        if (data.Length < 16) return false;
        var text = Encoding.ASCII.GetString(data[..Math.Min(data.Length, 2048)]);
        var idx = text.IndexOf("\r\nHost:", StringComparison.OrdinalIgnoreCase);
        if (idx < 0) return false;
        var start = idx + 7;
        var end = text.IndexOf('\r', start);
        if (end < 0) end = text.Length;
        var raw = text[start..end].Trim();
        var colon = raw.IndexOf(':');
        if (colon > 0) raw = raw[..colon];
        if (raw.Length == 0) return false;
        host = raw;
        return true;
    }
}
