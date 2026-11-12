using System.Net;
using Microsoft.Diagnostics.Tracing;
using Microsoft.Diagnostics.Tracing.Session;
using Microsoft.Extensions.Logging;

namespace NetworkControl.Engine.Windows.Dns;

/// <summary>一次 DNS 查詢的結果（來自 ETW）。</summary>
internal sealed record DnsQueryResult(string Name, IReadOnlyList<IPAddress> Addresses, int ProcessId);

/// <summary>解析 DNS-Client 事件 3008 中的 QueryResults 字段。</summary>
internal static class DnsResultParser
{
    /// <summary>
    /// 格式示例：<c>type:  5 www.bing.com.trafficmanager.net;::ffff:204.79.197.200;2620:1ec:c11::200;</c>。
    /// 「type: N name」是 CNAME 等非地址記錄，忽略；IPv4 結果以 IPv4 映射地址出現時還原為 IPv4。
    /// </summary>
    public static IReadOnlyList<IPAddress> Parse(string? results)
    {
        if (string.IsNullOrWhiteSpace(results))
        {
            return [];
        }

        var list = new List<IPAddress>();
        foreach (var token in results.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (token.StartsWith("type:", StringComparison.OrdinalIgnoreCase) || !IPAddress.TryParse(token, out var address))
            {
                continue;
            }

            if (address.IsIPv4MappedToIPv6)
            {
                address = address.MapToIPv4();
            }

            if (!list.Contains(address))
            {
                list.Add(address);
            }
        }

        return list;
    }
}

/// <summary>
/// 實時 ETW 會話：消費 Microsoft-Windows-DNS-Client 的「查詢完成」事件。
/// 不需要開啟該提供程序的事件日誌通道，但需要管理員權限。
/// </summary>
internal sealed partial class DnsClientMonitor(ILogger logger) : IDisposable
{
    private const string SessionName = "NetworkControl-DnsClient";
    private static readonly Guid DnsClientProvider = new("1C95126E-7EEA-49A9-A3FE-A378B03DDB4D");
    private const int QueryCompletedEventId = 3008;

    private TraceEventSession? _session;
    private Thread? _thread;

    public event Action<DnsQueryResult>? QueryCompleted;

    public void Start()
    {
        _session = new TraceEventSession(SessionName) { StopOnDispose = true };
        _session.EnableProvider(DnsClientProvider, TraceEventLevel.Informational);
        _session.Source.Dynamic.All += OnEvent;

        // Process() 會阻塞直到會話停止，放在專用線程上
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
            Name = "NetworkControl DNS ETW",
        };
        _thread.Start();
    }

    public void Dispose()
    {
        _session?.Dispose();
        _thread?.Join(TimeSpan.FromSeconds(2));
        _session = null;
    }

    private void OnEvent(TraceEvent e)
    {
        if ((int)e.ID != QueryCompletedEventId)
        {
            return;
        }

        try
        {
            if (e.PayloadByName("QueryStatus") is { } status && Convert.ToInt64(status, System.Globalization.CultureInfo.InvariantCulture) != 0)
            {
                return;
            }

            var name = e.PayloadByName("QueryName") as string;
            var addresses = DnsResultParser.Parse(e.PayloadByName("QueryResults") as string);
            if (!string.IsNullOrEmpty(name) && addresses.Count > 0)
            {
                QueryCompleted?.Invoke(new DnsQueryResult(name, addresses, e.ProcessID));
            }
        }
        catch (Exception ex) when (ex is FormatException or InvalidCastException or OverflowException)
        {
            LogBadEvent(logger, ex);
        }
    }

    [LoggerMessage(Level = LogLevel.Error, Message = "DNS ETW 會話異常結束，域名規則將不再更新")]
    private static partial void LogSessionFailed(ILogger logger, Exception exception);

    [LoggerMessage(Level = LogLevel.Debug, Message = "無法解析 DNS-Client 事件")]
    private static partial void LogBadEvent(ILogger logger, Exception exception);
}
