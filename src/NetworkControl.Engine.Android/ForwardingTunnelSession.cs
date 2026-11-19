using System.Net;
using global::Android.OS;
using global::Android.Systems;
using Java.IO;
using Microsoft.Extensions.Logging;
using NetworkControl.Engine.Vpn.Forwarding;

namespace NetworkControl.Engine.Android;

public sealed record ForwardingTunnelOptions(
    bool Enabled,
    bool UseDirectExit,
    string Socks5Host,
    int Socks5Port,
    string? Socks5Username,
    string? Socks5Password,
    string ConfigDir);

/// <summary>
/// 逐連線轉發隧道會話（對齊 Kotlin 版 hev-socks5-tunnel + AF_UNIX SOCK_SEQPACKET 雙向封包轉送）：
/// tunFd &lt;-&gt; C# 過濾追蹤層 &lt;-&gt; SOCK_SEQPACKET socketpair &lt;-&gt; libhev-socks5-tunnel.so &lt;-&gt; DirectSocksServer / 外部 SOCKS5。
/// </summary>
public sealed partial class ForwardingTunnelSession : IDisposable
{
    private readonly ParcelFileDescriptor _tunFd;
    private readonly ParcelFileDescriptor _relayFd;
    private readonly ParcelFileDescriptor _hevFd;
    private readonly DirectSocksServer? _directServer;
    private readonly ForwardingConnectionTracker _tracker;
    private readonly ILogger _logger;
    private readonly CancellationTokenSource _cts = new();
    private readonly Thread _hevThread;
    private readonly Thread _tunToHevThread;
    private readonly Thread _hevToTunThread;

    public ForwardingTunnelSession(
        ParcelFileDescriptor tunFd,
        ForwardingTunnelOptions options,
        ForwardingConnectionTracker tracker,
        ILogger logger)
    {
        _tunFd = tunFd;
        _tracker = tracker;
        _logger = logger;

        if (options.UseDirectExit)
        {
            _directServer = new DirectSocksServer();
            _directServer.Start();
        }

        var fdA = new FileDescriptor();
        var fdB = new FileDescriptor();
        Os.Socketpair(OsConstants.AfUnix, OsConstants.SockSeqpacket, 0, fdA, fdB);
        _relayFd = ParcelFileDescriptor.Dup(fdA)!;
        _hevFd = ParcelFileDescriptor.Dup(fdB)!;
        try { Os.Close(fdA); } catch { }
        try { Os.Close(fdB); } catch { }

        var socksHost = _directServer is not null ? "127.0.0.1" : options.Socks5Host;
        var socksPort = _directServer?.Port ?? options.Socks5Port;
        var socksUser = _directServer?.Username ?? options.Socks5Username;
        var socksPass = _directServer?.Password ?? options.Socks5Password;
        var cfgPath = HevTunnelInterop.WriteConfig(
            Path.Combine(options.ConfigDir, "hev-tunnel.yml"),
            socksHost,
            socksPort,
            socksUser,
            socksPass);

        _hevThread = new Thread(() =>
        {
            try
            {
                HevTunnelInterop.MainFromFile(cfgPath, _hevFd.Fd);
            }
            catch (Exception ex)
            {
                LogHevExited(_logger, ex);
            }
        })
        {
            IsBackground = true,
            Name = "hev-tunnel",
        };
        _hevThread.Start();

        _tunToHevThread = new Thread(() => RelayLoop(_tunFd, _relayFd, clientToHev: true))
        {
            IsBackground = true,
            Name = "tun-to-hev",
        };
        _hevToTunThread = new Thread(() => RelayLoop(_relayFd, _tunFd, clientToHev: false))
        {
            IsBackground = true,
            Name = "hev-to-tun",
        };
        _tunToHevThread.Start();
        _hevToTunThread.Start();
    }

    private void RelayLoop(ParcelFileDescriptor srcPfd, ParcelFileDescriptor dstPfd, bool clientToHev)
    {
        var buf = new byte[32_768];
        using var srcStream = new FileInputStream(srcPfd.FileDescriptor!);
        using var dstStream = new FileOutputStream(dstPfd.FileDescriptor!);
        try
        {
            while (!_cts.IsCancellationRequested)
            {
                var len = srcStream.Read(buf);
                if (len <= 0) break;

                if (clientToHev && TryExtractTcpKey(buf, len, out var key, out var dstIp, out var dstPort))
                {
                    if (_tracker.ConsumeCutIfRequested(key))
                    {
                        continue;
                    }

                    _tracker.Record(key, null, string.Empty, dstIp, dstPort, "TCP", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                }

                dstStream.Write(buf, 0, len);
            }
        }
        catch (Exception) when (_cts.IsCancellationRequested)
        {
        }
        catch (Exception ex)
        {
            LogRelayEnded(_logger, ex, clientToHev ? "tun->hev" : "hev->tun");
        }
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "hev-socks5-tunnel exited with error")]
    private static partial void LogHevExited(ILogger logger, Exception exception);

    [LoggerMessage(Level = LogLevel.Debug, Message = "Relay loop ended ({Direction})")]
    private static partial void LogRelayEnded(ILogger logger, Exception exception, string direction);

    private static bool TryExtractTcpKey(byte[] buf, int len, out string key, out string dstIp, out int dstPort)
    {
        key = string.Empty;
        dstIp = string.Empty;
        dstPort = 0;
        if (len < 40 || (buf[0] >> 4) != 4) return false;
        var ihl = (buf[0] & 0x0F) * 4;
        if (buf[9] != 6 || len < ihl + 20) return false;
        var srcPort = (buf[ihl] << 8) | buf[ihl + 1];
        dstPort = (buf[ihl + 2] << 8) | buf[ihl + 3];
        dstIp = new IPAddress(buf.AsSpan(16, 4)).ToString();
        key = $"{srcPort}->{dstIp}:{dstPort}";
        return true;
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { HevTunnelInterop.Quit(); } catch { }
        try { _relayFd.Close(); } catch { }
        try { _hevFd.Close(); } catch { }
        try { _tunFd.Close(); } catch { }
        if (_directServer is not null)
        {
            try { _directServer.DisposeAsync().AsTask().GetAwaiter().GetResult(); } catch { }
        }
        _cts.Dispose();
    }
}
