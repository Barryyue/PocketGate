using System.Diagnostics;
using System.Net.Sockets;
using System.Text;

namespace NetworkControl.Engine.Vpn.Forwarding;

public sealed record Socks5DiagnosticResult(
    bool PortReachable,
    bool AuthSucceeded,
    bool InternetReachable,
    long LatencyMs,
    string? ErrorStage = null,
    string? ErrorMessage = null);

/// <summary>
/// 外部 SOCKS5 代理三段式精準診斷（K-160）：
/// 1. 本機連接埠連通性；2. SOCKS5 握手與帳密認證；3. 經代理存取外網與往返延遲。
/// </summary>
public static class Socks5Probe
{
    public static async Task<Socks5DiagnosticResult> DiagnoseAsync(
        string host,
        int port,
        string? username = null,
        string? password = null,
        CancellationToken ct = default)
    {
        var sw = Stopwatch.StartNew();
        using var client = new TcpClient();
        try
        {
            using var connectCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            connectCts.CancelAfter(TimeSpan.FromSeconds(3));
            await client.ConnectAsync(host, port, connectCts.Token).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            return new Socks5DiagnosticResult(false, false, false, sw.ElapsedMilliseconds, "port", ex.Message);
        }

        try
        {
            var stream = client.GetStream();
            stream.ReadTimeout = 4000;
            stream.WriteTimeout = 4000;

            var hasAuth = !string.IsNullOrEmpty(username);
            var greet = hasAuth ? new byte[] { 0x05, 0x02, 0x00, 0x02 } : new byte[] { 0x05, 0x01, 0x00 };
            await stream.WriteAsync(greet, ct).ConfigureAwait(false);

            var methodReply = new byte[2];
            await ReadExactAsync(stream, methodReply, ct).ConfigureAwait(false);
            if (methodReply[0] != 0x05 || methodReply[1] == 0xFF)
            {
                return new Socks5DiagnosticResult(true, false, false, sw.ElapsedMilliseconds, "auth", "No acceptable auth method");
            }

            if (methodReply[1] == 0x02)
            {
                var uBytes = Encoding.UTF8.GetBytes(username ?? string.Empty);
                var pBytes = Encoding.UTF8.GetBytes(password ?? string.Empty);
                var authReq = new byte[3 + uBytes.Length + pBytes.Length];
                authReq[0] = 0x01;
                authReq[1] = (byte)uBytes.Length;
                Buffer.BlockCopy(uBytes, 0, authReq, 2, uBytes.Length);
                authReq[2 + uBytes.Length] = (byte)pBytes.Length;
                Buffer.BlockCopy(pBytes, 0, authReq, 3 + uBytes.Length, pBytes.Length);
                await stream.WriteAsync(authReq, ct).ConfigureAwait(false);

                var authReply = new byte[2];
                await ReadExactAsync(stream, authReply, ct).ConfigureAwait(false);
                if (authReply[1] != 0x00)
                {
                    return new Socks5DiagnosticResult(true, false, false, sw.ElapsedMilliseconds, "auth", "SOCKS5 authentication rejected");
                }
            }

            // 第 3 段：經代理發起 CONNECT 1.1.1.1:443 確認真正能出網
            var connectReq = new byte[] { 0x05, 0x01, 0x00, 0x01, 1, 1, 1, 1, 0x01, 0xBB };
            await stream.WriteAsync(connectReq, ct).ConfigureAwait(false);
            var head = new byte[4];
            await ReadExactAsync(stream, head, ct).ConfigureAwait(false);
            if (head[1] != 0x00)
            {
                return new Socks5DiagnosticResult(true, true, false, sw.ElapsedMilliseconds, "outbound", $"CONNECT failed (code {head[1]})");
            }

            var restLen = head[3] switch
            {
                0x01 => 6,
                0x04 => 18,
                0x03 => 1 + (await ReadByteAsync(stream, ct).ConfigureAwait(false)) + 2,
                _ => 0,
            };
            if (restLen > 0)
            {
                await ReadExactAsync(stream, new byte[restLen], ct).ConfigureAwait(false);
            }

            return new Socks5DiagnosticResult(true, true, true, sw.ElapsedMilliseconds);
        }
        catch (Exception ex)
        {
            return new Socks5DiagnosticResult(true, false, false, sw.ElapsedMilliseconds, "outbound", ex.Message);
        }
    }

    private static async Task<int> ReadByteAsync(Stream stream, CancellationToken ct)
    {
        var b = new byte[1];
        await ReadExactAsync(stream, b, ct).ConfigureAwait(false);
        return b[0];
    }

    private static async Task ReadExactAsync(Stream stream, byte[] buffer, CancellationToken ct)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var n = await stream.ReadAsync(buffer.AsMemory(offset), ct).ConfigureAwait(false);
            if (n <= 0) throw new EndOfStreamException("Unexpected EOF");
            offset += n;
        }
    }
}
