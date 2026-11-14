using System.Net;
using System.Threading.Channels;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;

namespace NetworkControl.Core.Traffic;

/// <summary>一個應用在某一分鐘內的流量增量。<see cref="AppId"/> 為 -1 表示無法識別的應用。</summary>
public readonly record struct TrafficDelta(long AppId, DateTimeOffset Minute, long BytesIn, long BytesOut);

/// <summary>流量時間軸上的一個分桶。</summary>
public sealed record TrafficBucket(DateTimeOffset Start, long BytesIn, long BytesOut);

/// <summary>按應用的流量合計。</summary>
public sealed record AppTraffic(long AppId, long BytesIn, long BytesOut)
{
    public long Total => BytesIn + BytesOut;
}

/// <summary>一段時間內的流量統計。</summary>
public sealed record TrafficSummary(long BytesIn, long BytesOut, IReadOnlyList<TrafficBucket> Timeline, IReadOnlyList<AppTraffic> TopApps);

/// <summary>【存儲實現】流量的持久化（FR-STA-01）。按分鐘 / 小時 / 天三級聚合保存。</summary>
public interface ITrafficRepository
{
    Task AddAsync(IReadOnlyCollection<TrafficDelta> deltas, CancellationToken cancellationToken);

    /// <summary>[from, to) 的流量時間軸（全部分桶，沒有數據的為 0）和用量最大的應用。</summary>
    Task<TrafficSummary> SummarizeAsync(DateTimeOffset from, DateTimeOffset to, TimeSpan bucket, int topCount, CancellationToken cancellationToken);

    /// <summary>[from, to) 內每個應用的流量合計（應用頁）。</summary>
    Task<IReadOnlyDictionary<long, AppTraffic>> ByAppAsync(DateTimeOffset from, DateTimeOffset to, CancellationToken cancellationToken);
}

/// <summary>
/// 流量聚合：引擎上報的樣本（按進程或可執行文件路徑）→ 解析應用身份 → 按（應用, 分鐘）累加 → 定期寫入存儲。
/// 引擎回調只做入隊（不阻塞），身份解析和寫入在後台完成。
/// </summary>
public sealed partial class TrafficAggregator : BackgroundService
{
    internal static readonly TimeSpan FlushInterval = TimeSpan.FromSeconds(15);
    private const int QueueCapacity = 16_384;

    private static readonly IPEndPoint Unspecified = new(IPAddress.Any, 0);

    private readonly Channel<TrafficSample> _queue = Channel.CreateBounded<TrafficSample>(new BoundedChannelOptions(QueueCapacity)
    {
        FullMode = BoundedChannelFullMode.DropOldest,
        SingleReader = true,
    });

    private readonly Dictionary<(long AppId, long Minute), (long In, long Out)> _pending = [];
    private readonly IAppIdentityResolver _resolver;
    private readonly ITrafficRepository? _repository;
    private readonly TimeProvider _time;
    private readonly ILogger<TrafficAggregator> _logger;

    public TrafficAggregator(IAppIdentityResolver resolver, IEnumerable<ITrafficRepository> repositories, TimeProvider time, ILogger<TrafficAggregator> logger)
    {
        _resolver = resolver;
        _repository = repositories.LastOrDefault(); // 沒有存儲實現（例如場景測試）時只丟棄樣本
        _time = time;
        _logger = logger;
    }

    /// <summary>引擎回調：只入隊。隊列滿時丟棄最舊的樣本，保證不阻塞引擎。</summary>
    public void Record(ReadOnlySpan<TrafficSample> samples)
    {
        foreach (var sample in samples)
        {
            if (sample.BytesIn > 0 || sample.BytesOut > 0)
            {
                _queue.Writer.TryWrite(sample);
            }
        }
    }

    /// <summary>把已入隊的樣本全部處理並寫入存儲（測試和停止時使用）。</summary>
    public async Task FlushAsync(CancellationToken cancellationToken)
    {
        while (_queue.Reader.TryRead(out var sample))
        {
            await AccumulateAsync(sample, cancellationToken);
        }

        await WriteAsync(cancellationToken);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(FlushInterval, _time);
        var reading = ReadLoopAsync(stoppingToken);
        try
        {
            while (await timer.WaitForNextTickAsync(stoppingToken))
            {
                await WriteAsync(stoppingToken);
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // 正常停止
        }

        await reading;
    }

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        await base.StopAsync(cancellationToken);
        try
        {
            // 停止時寫完已收到的數據
            await FlushAsync(cancellationToken);
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            LogWriteFailed(_logger, ex);
        }
    }

    private async Task ReadLoopAsync(CancellationToken cancellationToken)
    {
        try
        {
            await foreach (var sample in _queue.Reader.ReadAllAsync(cancellationToken))
            {
                await AccumulateAsync(sample, cancellationToken);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
    }

    private async Task AccumulateAsync(TrafficSample sample, CancellationToken cancellationToken)
    {
        var appId = -1L;
        if (sample.Process is not null || sample.ExecutablePath is not null)
        {
            try
            {
                // 解析器只使用進程和路徑；其餘字段是佔位
                var identity = await _resolver.ResolveAsync(
                    new FlowInfo
                    {
                        FlowId = 0,
                        Direction = Direction.Outbound,
                        Protocol = L4Protocol.Tcp,
                        Local = Unspecified,
                        Remote = Unspecified,
                        Process = sample.Process,
                        ExecutablePath = sample.ExecutablePath,
                        Timestamp = sample.Timestamp,
                    },
                    cancellationToken);
                appId = identity?.AppId ?? -1;
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                LogResolveFailed(_logger, ex);
            }
        }

        var minute = sample.Timestamp.ToUnixTimeMilliseconds() / 60_000;
        lock (_pending)
        {
            _pending.TryGetValue((appId, minute), out var current);
            _pending[(appId, minute)] = (current.In + sample.BytesIn, current.Out + sample.BytesOut);
        }
    }

    private async Task WriteAsync(CancellationToken cancellationToken)
    {
        TrafficDelta[] batch;
        lock (_pending)
        {
            if (_pending.Count == 0)
            {
                return;
            }

            batch = _pending.Select(p => new TrafficDelta(p.Key.AppId, DateTimeOffset.FromUnixTimeMilliseconds(p.Key.Minute * 60_000), p.Value.In, p.Value.Out)).ToArray();
            _pending.Clear();
        }

        if (_repository is null)
        {
            return;
        }

        try
        {
            await _repository.AddAsync(batch, cancellationToken);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            // 寫入失敗只丟失這一批統計，不影響攔截
            LogWriteFailed(_logger, ex);
        }
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "流量樣本的應用解析失敗")]
    private static partial void LogResolveFailed(ILogger logger, Exception exception);

    [LoggerMessage(Level = LogLevel.Warning, Message = "寫入流量統計失敗")]
    private static partial void LogWriteFailed(ILogger logger, Exception exception);
}
