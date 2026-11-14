using System.Net;
using NetworkControl.Core.Common;
using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Activity;

/// <summary>一條已判決的連接事件。</summary>
public sealed record ConnectionEvent(
    DateTimeOffset Timestamp,
    long? AppId,
    string? AppPath,
    string? AppName,
    Direction Direction,
    L4Protocol Protocol,
    IPEndPoint Local,
    IPEndPoint Remote,
    string? Hostname,
    Verdict Verdict);

/// <summary>【擴展點】連接事件的消費者：存儲寫入、實時推送、統計等。實現必須非阻塞。</summary>
public interface IActivityConsumer
{
    void Consume(ConnectionEvent connectionEvent);
}

/// <summary>判決管線發布連接事件的入口。</summary>
public interface IActivitySink
{
    void Publish(ConnectionEvent connectionEvent);
}

/// <summary>把連接事件分發給所有註冊的消費者。</summary>
public sealed class ActivityHub(IEnumerable<IActivityConsumer> consumers) : IActivitySink
{
    private readonly IActivityConsumer[] _consumers = consumers.ToArray();

    public void Publish(ConnectionEvent connectionEvent)
    {
        foreach (var consumer in _consumers)
        {
            consumer.Consume(connectionEvent);
        }
    }
}

/// <summary>實時連接事件流，供 UI 的日誌頁面訂閱。</summary>
public sealed class LiveActivityFeed : IActivityConsumer
{
    public Broadcaster<ConnectionEvent> Events { get; } = new(capacityPerSubscriber: 4096);

    public void Consume(ConnectionEvent connectionEvent) => Events.Publish(connectionEvent);
}

/// <summary>日誌查詢條件。</summary>
public sealed record LogQuery
{
    public DateTimeOffset? From { get; init; }

    public DateTimeOffset? To { get; init; }

    public long? AppId { get; init; }

    public VerdictAction? Action { get; init; }

    /// <summary>匹配應用路徑、域名或遠端 IP 的關鍵字。</summary>
    public string? Keyword { get; init; }

    public int Limit { get; init; } = 200;

    /// <summary>分頁游標：返回 ID 小於此值的記錄。</summary>
    public long? BeforeId { get; init; }
}

/// <summary>聚合後的日誌條目（同一分鐘內的相同連接合併計數）。</summary>
public sealed record LogEntry(
    long Id,
    DateTimeOffset FirstSeen,
    DateTimeOffset LastSeen,
    long? AppId,
    string? AppPath,
    string? AppName,
    Direction Direction,
    L4Protocol Protocol,
    IPAddress RemoteAddress,
    int RemotePort,
    string? Hostname,
    Verdict Verdict,
    int Count,
    IPEndPoint? Local = null);

/// <summary>活動統計的查詢條件：時間範圍 [From, To) 按 <see cref="BucketSize"/> 分桶。</summary>
public sealed record ActivitySummaryQuery
{
    public required DateTimeOffset From { get; init; }

    public required DateTimeOffset To { get; init; }

    /// <summary>時間軸的分桶大小，至少 1 分鐘（日誌按分鐘聚合）。</summary>
    public TimeSpan BucketSize { get; init; } = TimeSpan.FromHours(1);

    /// <summary>排行榜返回的條目數。</summary>
    public int TopCount { get; init; } = 8;
}

/// <summary>時間軸上的一個分桶。</summary>
public sealed record ActivityBucket(DateTimeOffset Start, long Allowed, long Blocked);

/// <summary>排行榜條目：應用（<see cref="AppId"/> 有值或為未知應用）或目標（域名 / IP）。</summary>
public sealed record ActivityTopItem(string Key, string? Label, long? AppId, long Allowed, long Blocked, string? Path = null)
{
    public long Total => Allowed + Blocked;
}

/// <summary>一段時間內的連接統計（總覽儀表板）。</summary>
public sealed record ActivitySummary(
    long Allowed,
    long Blocked,
    int AppCount,
    int DestinationCount,
    IReadOnlyList<ActivityBucket> Timeline,
    IReadOnlyList<ActivityTopItem> TopApps,
    IReadOnlyList<ActivityTopItem> TopDestinations);

/// <summary>一個應用在一段時間內的連接統計（應用頁）。未識別的應用 <see cref="AppId"/> 為 null，按路徑區分。</summary>
public sealed record AppActivity(long? AppId, string? Path, string? Name, long Allowed, long Blocked, DateTimeOffset LastSeen);

/// <summary>【存儲實現】日誌查詢。</summary>
public interface IActivityQuery
{
    /// <summary>按應用統計 [from, to) 內的連接，按最近連接時間倒序。</summary>
    Task<IReadOnlyList<AppActivity>> ListAppActivityAsync(DateTimeOffset from, DateTimeOffset to, int limit, CancellationToken cancellationToken);

    Task<IReadOnlyList<LogEntry>> QueryAsync(LogQuery query, CancellationToken cancellationToken);

    /// <summary>統計一段時間內的允許 / 阻斷次數、時間軸和排行榜。時間軸包含範圍內的全部分桶（沒有記錄的為 0）。</summary>
    Task<ActivitySummary> SummarizeAsync(ActivitySummaryQuery query, CancellationToken cancellationToken);
}
