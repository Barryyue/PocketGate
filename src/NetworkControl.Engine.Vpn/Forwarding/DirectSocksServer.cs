using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace NetworkControl.Engine.Vpn.Forwarding;

/// <summary>
/// 內建直連 SOCKS5 出口伺服器（K-098）：
/// - 只監聽 127.0.0.1 隨機埠、強制 RFC 1929 隨機帳密認證，拒絕無認證客戶端；
/// - 支援 IPv4 / IPv6 / 域名 CONNECT 與雙向中繼；
/// - 可選注入實體網路綁定（Android 上透過 ConnectivityManager / Network.BindSocket）。
/// </summary>
public sealed class DirectSocksServer : IAsyncDisposable
{
    private readonly TcpListener _listener;
    private readonly Func<Socket, CancellationToken, Task>? _bindSocket;
    private readonly Func<string, CancellationToken, Task<IPAddress[]>>? _resolveHost;
    private readonly CancellationTokenSource _cts = new();
    private readonly ConcurrentDictionary<Socket, byte> _activeSockets = new();
    private Task? _acceptLoop;

    public int Port { get; }
    public string Username { get; }
    public string Password { get; }

    public DirectSocksServer(
        Func<Socket, CancellationToken, Task>? bindSocket = null,
        Func<string, CancellationToken, Task<IPAddress[]>>? resolveHost = null)
    {
        _bindSocket = bindSocket;
        _resolveHost = resolveHost;
        Username = Convert.ToHexString(RandomNumberGenerator.GetBytes(8)).ToLowerInvariant();
        Password = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        _listener = new TcpListener(IPAddress.Loopback, 0);
        _listener.Start();
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
    }

    public void Start()
    {
        _acceptLoop = Task.Run(AcceptLoopAsync);
    }

    private async Task AcceptLoopAsync()
    {
        try
        {
            while (!_cts.IsCancellationRequested)
            {
                var client = await _listener.AcceptTcpClientAsync(_cts.Token).ConfigureAwait(false);
                _ = Task.Run(() => HandleClientAsync(client, _cts.Token));
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken ct)
    {
        using (client)
        {
            Socket? outbound = null;
            try
            {
                client.NoDelay = true;
                var stream = client.GetStream();

                var header = new byte[2];
                if (!await ReadExactAsync(stream, header, ct).ConfigureAwait(false) || header[0] != 0x05) return;
                var nMethods = header[1];
                var methods = new byte[nMethods];
                if (nMethods > 0 && !await ReadExactAsync(stream, methods, ct).ConfigureAwait(false)) return;

                if (!methods.Contains((byte)0x02))
                {
                    await stream.WriteAsync(new byte[] { 0x05, 0xFF }, ct).ConfigureAwait(false);
                    return;
                }

                await stream.WriteAsync(new byte[] { 0x05, 0x02 }, ct).ConfigureAwait(false);
                if (!await AuthenticateAsync(stream, ct).ConfigureAwait(false))
                {
                    await stream.WriteAsync(new byte[] { 0x01, 0x01 }, ct).ConfigureAwait(false);
                    return;
                }

                await stream.WriteAsync(new byte[] { 0x01, 0x00 }, ct).ConfigureAwait(false);

                var reqHead = new byte[4];
                if (!await ReadExactAsync(stream, reqHead, ct).ConfigureAwait(false) || reqHead[0] != 0x05) return;
                if (reqHead[1] != 0x01)
                {
                    await SendReplyAsync(stream, 0x07, ct).ConfigureAwait(false);
                    return;
                }

                var (host, port) = await ReadTargetAsync(stream, reqHead[3], ct).ConfigureAwait(false);
                if (host is null)
                {
                    await SendReplyAsync(stream, 0x08, ct).ConfigureAwait(false);
                    return;
                }

                outbound = await ConnectTargetAsync(host, port, ct).ConfigureAwait(false);
                if (outbound is null)
                {
                    await SendReplyAsync(stream, 0x05, ct).ConfigureAwait(false);
                    return;
                }

                _activeSockets.TryAdd(outbound, 0);
                await SendReplyAsync(stream, 0x00, ct).ConfigureAwait(false);

                using var outStream = new NetworkStream(outbound, ownsSocket: false);
                var up = CopyHalfAsync(stream, outStream, outbound, ct);
                var down = CopyHalfAsync(outStream, stream, client.Client, ct);
                await Task.WhenAll(up, down).ConfigureAwait(false);
            }
            catch
            {
            }
            finally
            {
                if (outbound is not null)
                {
                    _activeSockets.TryRemove(outbound, out _);
                    try { outbound.Dispose(); } catch { }
                }
            }
        }
    }

    private async Task<bool> AuthenticateAsync(NetworkStream stream, CancellationToken ct)
    {
        var ver = new byte[1];
        if (!await ReadExactAsync(stream, ver, ct).ConfigureAwait(false) || ver[0] != 0x01) return false;
        var uLen = new byte[1];
        if (!await ReadExactAsync(stream, uLen, ct).ConfigureAwait(false)) return false;
        var uBytes = new byte[uLen[0]];
        if (uLen[0] > 0 && !await ReadExactAsync(stream, uBytes, ct).ConfigureAwait(false)) return false;

        var pLen = new byte[1];
        if (!await ReadExactAsync(stream, pLen, ct).ConfigureAwait(false)) return false;
        var pBytes = new byte[pLen[0]];
        if (pLen[0] > 0 && !await ReadExactAsync(stream, pBytes, ct).ConfigureAwait(false)) return false;

        return string.Equals(Encoding.UTF8.GetString(uBytes), Username, StringComparison.Ordinal) &&
               string.Equals(Encoding.UTF8.GetString(pBytes), Password, StringComparison.Ordinal);
    }

    private static async Task<(string? Host, int Port)> ReadTargetAsync(NetworkStream stream, byte atyp, CancellationToken ct)
    {
        string? host = null;
        switch (atyp)
        {
            case 0x01:
                var ipv4 = new byte[4];
                if (!await ReadExactAsync(stream, ipv4, ct).ConfigureAwait(false)) return (null, 0);
                host = new IPAddress(ipv4).ToString();
                break;
            case 0x03:
                var lenBuf = new byte[1];
                if (!await ReadExactAsync(stream, lenBuf, ct).ConfigureAwait(false)) return (null, 0);
                var dom = new byte[lenBuf[0]];
                if (!await ReadExactAsync(stream, dom, ct).ConfigureAwait(false)) return (null, 0);
                host = Encoding.UTF8.GetString(dom);
                break;
            case 0x04:
                var ipv6 = new byte[16];
                if (!await ReadExactAsync(stream, ipv6, ct).ConfigureAwait(false)) return (null, 0);
                host = new IPAddress(ipv6).ToString();
                break;
            default:
                return (null, 0);
        }

        var portBuf = new byte[2];
        if (!await ReadExactAsync(stream, portBuf, ct).ConfigureAwait(false)) return (null, 0);
        var port = (portBuf[0] << 8) | portBuf[1];
        return (host, port);
    }

    private async Task<Socket?> ConnectTargetAsync(string host, int port, CancellationToken ct)
    {
        IPAddress[] addresses;
        if (IPAddress.TryParse(host, out var parsed))
        {
            addresses = [parsed];
        }
        else if (_resolveHost is not null)
        {
            addresses = await _resolveHost(host, ct).ConfigureAwait(false);
        }
        else
        {
            addresses = await System.Net.Dns.GetHostAddressesAsync(host, ct).ConfigureAwait(false);
        }

        // IPv4 優先，避免純 IPv4 虛擬網卡環境下先試 IPv6 浪費逾時。
        var ordered = addresses.OrderBy(a => a.AddressFamily == AddressFamily.InterNetwork ? 0 : 1);
        foreach (var ip in ordered)
        {
            var sock = new Socket(ip.AddressFamily, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
            try
            {
                if (_bindSocket is not null)
                {
                    await _bindSocket(sock, ct).ConfigureAwait(false);
                }

                using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                timeoutCts.CancelAfter(TimeSpan.FromSeconds(3));
                await sock.ConnectAsync(new IPEndPoint(ip, port), timeoutCts.Token).ConfigureAwait(false);
                return sock;
            }
            catch
            {
                sock.Dispose();
            }
        }

        return null;
    }

    private static async Task CopyHalfAsync(Stream src, Stream dst, Socket dstSocket, CancellationToken ct)
    {
        var buf = new byte[16 * 1024];
        try
        {
            while (true)
            {
                var n = await src.ReadAsync(buf, ct).ConfigureAwait(false);
                if (n <= 0) break;
                await dst.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
            }

            try { dstSocket.Shutdown(SocketShutdown.Send); } catch { }
        }
        catch
        {
        }
    }

    private static async Task SendReplyAsync(NetworkStream stream, byte rep, CancellationToken ct)
    {
        var reply = new byte[] { 0x05, rep, 0x00, 0x01, 0, 0, 0, 0, 0, 0 };
        await stream.WriteAsync(reply, ct).ConfigureAwait(false);
    }

    private static async Task<bool> ReadExactAsync(Stream stream, byte[] buf, CancellationToken ct)
    {
        var offset = 0;
        while (offset < buf.Length)
        {
            var n = await stream.ReadAsync(buf.AsMemory(offset), ct).ConfigureAwait(false);
            if (n <= 0) return false;
            offset += n;
        }

        return true;
    }

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        try { _listener.Stop(); } catch { }
        foreach (var sock in _activeSockets.Keys)
        {
            try { sock.Dispose(); } catch { }
        }

        if (_acceptLoop is not null)
        {
            try { await _acceptLoop.ConfigureAwait(false); } catch { }
        }

        _cts.Dispose();
    }
}
