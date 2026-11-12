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
    int Count);

/// <summary>【存儲實現】日誌查詢。</summary>
public interface IActivityQuery
{
    Task<IReadOnlyList<LogEntry>> QueryAsync(LogQuery query, CancellationToken cancellationToken);
}
