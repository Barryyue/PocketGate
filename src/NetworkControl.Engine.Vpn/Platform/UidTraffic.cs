using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Traffic;

namespace NetworkControl.Engine.Vpn.Platform;

/// <summary>系統統計中一個 UID 在一個時間段內的流量（Android：NetworkStatsManager 的一個 Bucket）。</summary>
public readonly record struct UidTrafficBucket(int Uid, DateTimeOffset Start, DateTimeOffset End, long RxBytes, long TxBytes);

/// <summary>
/// 【平台實現】按 UID 的流量統計（Android：NetworkStatsManager）。允許的應用不經過 VPN，
/// 引擎看不到它們的流量，只能讀取系統的統計。
/// </summary>
public interface IUidTrafficSource
{
    /// <summary>是否有權讀取（Android 需要用戶在系統設定中授予「使用情況存取權」）。</summary>
    bool IsAvailable { get; }

    /// <summary>與 [from, to) 重疊的全部分桶，Wi-Fi 和行動網路合併。沒有權限時返回空列表。</summary>
    IReadOnlyList<UidTrafficBucket> Query(DateTimeOffset from, DateTimeOffset to);
}

/// <summary>
/// 定期讀取系統的按 UID 流量統計，轉成增量交給 <see cref="TrafficAggregator"/>。
/// </summary>
/// <remarks>
/// 系統分桶（通常 1–2 小時）在進行中會持續增長，每次查詢都會返回同一個分桶的最新累計值：
/// 記住每個（UID, 分桶起點）上次看到的值，只上報增長的部分，避免重複計數。
/// 這張表保存在文件中，進程被系統殺死重啟後不會把最近的分桶再算一遍。
/// </remarks>
public sealed partial class UidTrafficCollector : BackgroundService
{
    internal static readonly TimeSpan Interval = TimeSpan.FromMinutes(10);

    /// <summary>每次查詢回溯的時間：覆蓋進程停止期間系統繼續累計的分桶。</summary>
    internal static readonly TimeSpan Lookback = TimeSpan.FromHours(26);

    private readonly IUidTrafficSource _source;
    private readonly IInstalledAppCatalog _catalog;
    private readonly TrafficAggregator _aggregator;
    private readonly TimeProvider _time;
    private readonly ILogger<UidTrafficCollector> _logger;
    private readonly string? _statePath;
    private readonly Lock _gate = new();
    private Dictionary<string, long[]> _seen;

    public UidTrafficCollector(
        IUidTrafficSource source,
        IInstalledAppCatalog catalog,
        TrafficAggregator aggregator,
        TimeProvider time,
        ILogger<UidTrafficCollector> logger,
        string? statePath = null)
    {
        _source = source;
        _catalog = catalog;
        _aggregator = aggregator;
        _time = time;
        _logger = logger;
        _statePath = statePath;
        _seen = Load(statePath);
    }

    /// <summary>立即採集一次（界面打開用量頁時調用，讓數據及時）。返回上報的樣本數。</summary>
    public int CollectNow()
    {
        if (!_source.IsAvailable)
        {
            return 0;
        }

        var now = _time.GetUtcNow();
        IReadOnlyList<UidTrafficBucket> buckets;
        try
        {
            buckets = _source.Query(now - Lookback, now);
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            LogQueryFailed(_logger, ex);
            return 0;
        }

        // 系統可能按網絡類型、前後台、計費狀態把同一時段拆成多個分桶：先按（UID, 起點）合併
        var merged = buckets
            .Where(b => b.Uid >= 0)
            .GroupBy(b => (b.Uid, b.Start))
            .Select(g => new UidTrafficBucket(g.Key.Uid, g.Key.Start, g.Max(b => b.End), g.Sum(b => b.RxBytes), g.Sum(b => b.TxBytes)));

        var samples = new List<TrafficSample>();
        lock (_gate)
        {
            foreach (var bucket in merged)
            {
                var key = $"{bucket.Uid}:{bucket.Start.ToUnixTimeMilliseconds()}";
                var previous = _seen.GetValueOrDefault(key) ?? [0, 0];

                // 系統重建統計後同一分桶的值可能變小：此時把新值當作增量
                var rx = bucket.RxBytes >= previous[0] ? bucket.RxBytes - previous[0] : bucket.RxBytes;
                var tx = bucket.TxBytes >= previous[1] ? bucket.TxBytes - previous[1] : bucket.TxBytes;
                _seen[key] = [bucket.RxBytes, bucket.TxBytes];
                if (rx == 0 && tx == 0)
                {
                    continue;
                }

                var package = _catalog.GetPackagesForUid(bucket.Uid).FirstOrDefault();
                var path = package is not null ? PackageAppPath.ForPackage(package) : PackageAppPath.ForUid(bucket.Uid);
                samples.Add(new TrafficSample(null, path, rx, tx, bucket.Start));
            }

            // 只保留回溯窗口內的分桶
            var cutoff = (now - Lookback - TimeSpan.FromHours(4)).ToUnixTimeMilliseconds();
            foreach (var key in _seen.Keys.Where(k => long.Parse(k.AsSpan(k.IndexOf(':') + 1), System.Globalization.CultureInfo.InvariantCulture) < cutoff).ToArray())
            {
                _seen.Remove(key);
            }

            Save();
        }

        if (samples.Count > 0)
        {
            _aggregator.Record(samples.ToArray());
        }

        return samples.Count;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(Interval, _time);
        try
        {
            do
            {
                CollectNow();
            }
            while (await timer.WaitForNextTickAsync(stoppingToken));
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
        }
    }

    private static Dictionary<string, long[]> Load(string? path)
    {
        try
        {
            if (path is not null && File.Exists(path))
            {
                return JsonSerializer.Deserialize(File.ReadAllText(path), UidTrafficJsonContext.Default.DictionaryStringInt64Array) ?? [];
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            // 狀態損壞時從頭開始：最壞情況是最近一天的流量被重複計入一次
        }

        return [];
    }

    private void Save()
    {
        if (_statePath is null)
        {
            return;
        }

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_statePath)!);
            var temporary = _statePath + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(_seen, UidTrafficJsonContext.Default.DictionaryStringInt64Array));
            File.Move(temporary, _statePath, overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            LogSaveFailed(_logger, ex);
        }
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "讀取系統流量統計失敗")]
    private static partial void LogQueryFailed(ILogger logger, Exception exception);

    [LoggerMessage(Level = LogLevel.Warning, Message = "保存流量採集狀態失敗")]
    private static partial void LogSaveFailed(ILogger logger, Exception exception);
}

[JsonSerializable(typeof(Dictionary<string, long[]>))]
internal sealed partial class UidTrafficJsonContext : JsonSerializerContext;
