using Microsoft.Diagnostics.Tracing;
using Microsoft.Diagnostics.Tracing.Session;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Engine;

namespace NetworkControl.Engine.Windows.Traffic;

/// <summary>
/// 按進程統計流量（FR-STA-01）：實時 ETW 會話消費 Microsoft-Windows-Kernel-Network 的 TCP / UDP 收發事件，
/// 按 PID 累加字節數，定期換算成 <see cref="TrafficSample"/> 交給核心。需要管理員權限（服務以 SYSTEM 運行）。
/// </summary>
/// <remarks>
/// 事件按報文觸發，數量很大：回調裡只做加法，進程解析和上報在定時器線程上完成。
/// 進程在兩次上報之間退出時解析不到路徑，這部分流量記為「無法識別的應用」。
/// </remarks>
internal sealed partial class NetworkTrafficMonitor(IProcessInfoProvider processes, TimeProvider time, ILogger logger) : IDisposable
{
    internal static readonly TimeSpan FlushInterval = TimeSpan.FromSeconds(10);

    private const string SessionName = "NetworkControl-KernelNetwork";
    private static readonly Guid KernelNetworkProvider = new("7DD42A49-5329-4832-8DFD-43D979153A88");

    // KERNEL_NETWORK_KEYWORD_IPV4 | KERNEL_NETWORK_KEYWORD_IPV6
    private const ulong Keywords = 0x10 | 0x20;

    private readonly Lock _gate = new();
    private Dictionary<int, (long In, long Out)> _pending = [];
    private TraceEventSession? _session;
    private Thread? _thread;
    private ITimer? _timer;
    private IEngineHost? _host;
    private long _events;

    public long EventCount => Interlocked.Read(ref _events);

    /// <summary>事件 ID → 方向（true 為接收）。TCP / UDP，IPv4 / IPv6 的「已發送 / 已接收數據」事件。</summary>
    internal static bool? Classify(int eventId) => eventId switch
    {
        10 or 26 or 42 or 58 => false, // TCPv4 / TCPv6 / UDPv4 / UDPv6 發送
        11 or 27 or 43 or 59 => true,  // TCPv4 / TCPv6 / UDPv4 / UDPv6 接收
        _ => null,
    };

    /// <summary>指定上報目標（不啟動 ETW 會話，測試使用）。</summary>
    internal void Attach(IEngineHost host) => _host = host;

    public void Start(IEngineHost host)
    {
        Attach(host);
        _session = new TraceEventSession(SessionName) { StopOnDispose = true };
        _session.EnableProvider(KernelNetworkProvider, TraceEventLevel.Informational, Keywords);
        _session.Source.Dynamic.All += OnEvent;
        _thread = new Thread(() =>
        {
            try
            {
                _session.Source.Process();
            }
            catch (Exception ex)
            {
                LogSessionFailed(logger, ex);
            }
        })
        {
            IsBackground = true,
            Name = "NetworkControl Kernel-Network ETW",
        };
        _thread.Start();
        _timer = time.CreateTimer(_ => Flush(), null, FlushInterval, FlushInterval);
    }

    public void Dispose()
    {
        _timer?.Dispose();
        _timer = null;
        _session?.Dispose();
        _thread?.Join(TimeSpan.FromSeconds(2));
        _session = null;
        Flush();
        _host = null;
    }

    /// <summary>累加一個事件（測試直接調用）。</summary>
    internal void Add(int processId, bool received, long bytes)
    {
        if (processId <= 0 || bytes <= 0)
        {
            return;
        }

        lock (_gate)
        {
            _pending.TryGetValue(processId, out var current);
            _pending[processId] = received ? (current.In + bytes, current.Out) : (current.In, current.Out + bytes);
        }
    }

    /// <summary>把累計的字節數按進程上報。</summary>
    internal void Flush()
    {
        Dictionary<int, (long In, long Out)> batch;
        lock (_gate)
        {
            if (_pending.Count == 0)
            {
                return;
            }

            batch = _pending;
            _pending = [];
        }

        var now = time.GetUtcNow();
        var samples = new TrafficSample[batch.Count];
        var i = 0;
        foreach (var (pid, (bytesIn, bytesOut)) in batch)
        {
            samples[i++] = processes.TryGetProcess(pid, out var snapshot)
                ? new TrafficSample(snapshot.Process, snapshot.ExecutablePath, bytesIn, bytesOut, now)
                : new TrafficSample(null, null, bytesIn, bytesOut, now);
        }

        _host?.OnTraffic(samples);
    }

    private void OnEvent(TraceEvent e)
    {
        if (Classify((int)e.ID) is not { } received)
        {
            return;
        }

        Interlocked.Increment(ref _events);
        try
        {
            var pid = Convert.ToInt32(e.PayloadByName("PID"), System.Globalization.CultureInfo.InvariantCulture);
            var size = Convert.ToInt64(e.PayloadByName("size"), System.Globalization.CultureInfo.InvariantCulture);
            Add(pid, received, size);
        }
        catch (Exception ex) when (ex is FormatException or InvalidCastException or OverflowException)
        {
            LogBadEvent(logger, ex);
        }
    }

    [LoggerMessage(Level = LogLevel.Error, Message = "Kernel-Network ETW 會話異常結束，流量統計將停止更新")]
    private static partial void LogSessionFailed(ILogger logger, Exception exception);

    [LoggerMessage(Level = LogLevel.Debug, Message = "無法解析 Kernel-Network 事件")]
    private static partial void LogBadEvent(ILogger logger, Exception exception);
}
