using Microsoft.Data.Sqlite;
using NetworkControl.Core.Traffic;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Activity;

/// <summary>
/// 流量存儲：每個增量同時累加到 traffic_minute / traffic_hour / traffic_day（UTC 對齊）。
/// 分鐘表只保留最近 3 天（短時間範圍的精確查詢）；更長的範圍用小時表，界面按本地時間分桶時不受 UTC 日界影響。
/// </summary>
internal sealed class SqliteTrafficRepository(ActivityDatabase db, TimeProvider time) : ITrafficRepository
{
    private const long MinuteMs = 60_000;
    private const long HourMs = 3_600_000;
    private const long DayMs = 86_400_000;
    private static readonly TimeSpan MinuteRetention = TimeSpan.FromDays(3);

    /// <summary>不超過這個跨度的查詢使用分鐘表。</summary>
    private static readonly TimeSpan MinuteTableSpan = TimeSpan.FromDays(2);

    private long _lastCleanup;

    public async Task AddAsync(IReadOnlyCollection<TrafficDelta> deltas, CancellationToken cancellationToken)
    {
        if (deltas.Count == 0)
        {
            return;
        }

        await using var connection = await db.OpenAsync(cancellationToken);
        await using var tx = (SqliteTransaction)await connection.BeginTransactionAsync(cancellationToken);
        foreach (var (table, unit) in new[] { ("traffic_minute", MinuteMs), ("traffic_hour", HourMs), ("traffic_day", DayMs) })
        {
            await using var cmd = connection.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = $"""
                INSERT INTO {table} (app_id, ts, bytes_in, bytes_out) VALUES ($app, $ts, $in, $out)
                ON CONFLICT (app_id, ts) DO UPDATE SET bytes_in = bytes_in + excluded.bytes_in, bytes_out = bytes_out + excluded.bytes_out;
                """;
            var pApp = cmd.Parameters.Add("$app", SqliteType.Integer);
            var pTs = cmd.Parameters.Add("$ts", SqliteType.Integer);
            var pIn = cmd.Parameters.Add("$in", SqliteType.Integer);
            var pOut = cmd.Parameters.Add("$out", SqliteType.Integer);
            await cmd.PrepareAsync(cancellationToken);
            foreach (var delta in deltas)
            {
                pApp.Value = delta.AppId;
                pTs.Value = delta.Minute.ToUnixMs() / unit * unit;
                pIn.Value = delta.BytesIn;
                pOut.Value = delta.BytesOut;
                await cmd.ExecuteNonQueryAsync(cancellationToken);
            }
        }

        await CleanupAsync(connection, tx, cancellationToken);
        await tx.CommitAsync(cancellationToken);
    }

    public async Task<TrafficSummary> SummarizeAsync(DateTimeOffset from, DateTimeOffset to, TimeSpan bucket, int topCount, CancellationToken cancellationToken)
    {
        var (table, unit) = TableFor(from, to);
        var bucketMs = Math.Max(unit, (long)bucket.TotalMilliseconds);
        var fromMs = from.ToUnixMs();
        var toMs = Math.Max(fromMs, to.ToUnixMs());
        var bucketCount = (int)Math.Min(10_000, (toMs - fromMs + bucketMs - 1) / bucketMs);
        var bytesIn = new long[bucketCount];
        var bytesOut = new long[bucketCount];

        await using var connection = await db.OpenAsync(cancellationToken);
        await using (var cmd = connection.CreateCommand())
        {
            // 行的 ts 是分鐘 / 小時的起點：起點落在範圍內的行計入，按 (ts - from) 分桶
            cmd.CommandText = $"""
                SELECT (ts - $from) / $bucket AS b, SUM(bytes_in), SUM(bytes_out)
                FROM {table} WHERE ts >= $from AND ts < $to GROUP BY b;
                """;
            cmd.With("$from", fromMs).With("$to", toMs).With("$bucket", bucketMs);
            await using var r = await cmd.ExecuteReaderAsync(cancellationToken);
            while (await r.ReadAsync(cancellationToken))
            {
                var index = r.GetInt64(0);
                if (index >= 0 && index < bucketCount)
                {
                    bytesIn[index] += r.GetInt64(1);
                    bytesOut[index] += r.GetInt64(2);
                }
            }
        }

        var timeline = new TrafficBucket[bucketCount];
        for (var i = 0; i < bucketCount; i++)
        {
            timeline[i] = new TrafficBucket(SqliteExtensions.FromUnixMs(fromMs + (i * bucketMs)), bytesIn[i], bytesOut[i]);
        }

        var top = new List<AppTraffic>();
        if (topCount > 0)
        {
            await using var cmd = connection.CreateCommand();
            cmd.CommandText = $"""
                SELECT app_id, SUM(bytes_in), SUM(bytes_out) FROM {table}
                WHERE ts >= $from AND ts < $to GROUP BY app_id ORDER BY SUM(bytes_in) + SUM(bytes_out) DESC LIMIT $top;
                """;
            cmd.With("$from", fromMs).With("$to", toMs).With("$top", Math.Min(topCount, 100));
            await using var r = await cmd.ExecuteReaderAsync(cancellationToken);
            while (await r.ReadAsync(cancellationToken))
            {
                top.Add(new AppTraffic(r.GetInt64(0), r.GetInt64(1), r.GetInt64(2)));
            }
        }

        return new TrafficSummary(bytesIn.Sum(), bytesOut.Sum(), timeline, top);
    }

    public async Task<IReadOnlyDictionary<long, AppTraffic>> ByAppAsync(DateTimeOffset from, DateTimeOffset to, CancellationToken cancellationToken)
    {
        var (table, _) = TableFor(from, to);
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = $"SELECT app_id, SUM(bytes_in), SUM(bytes_out) FROM {table} WHERE ts >= $from AND ts < $to GROUP BY app_id;";
        cmd.With("$from", from.ToUnixMs()).With("$to", to.ToUnixMs());
        var result = new Dictionary<long, AppTraffic>();
        await using var r = await cmd.ExecuteReaderAsync(cancellationToken);
        while (await r.ReadAsync(cancellationToken))
        {
            result[r.GetInt64(0)] = new AppTraffic(r.GetInt64(0), r.GetInt64(1), r.GetInt64(2));
        }

        return result;
    }

    private (string Table, long Unit) TableFor(DateTimeOffset from, DateTimeOffset to) =>
        to - from <= MinuteTableSpan && from >= time.GetUtcNow() - MinuteRetention
            ? ("traffic_minute", MinuteMs)
            : ("traffic_hour", HourMs);

    /// <summary>每小時最多清理一次過期的分鐘數據。</summary>
    private async Task CleanupAsync(SqliteConnection connection, SqliteTransaction tx, CancellationToken cancellationToken)
    {
        var now = time.GetUtcNow().ToUnixMs();
        if (now - Interlocked.Read(ref _lastCleanup) < HourMs)
        {
            return;
        }

        Interlocked.Exchange(ref _lastCleanup, now);
        await using var cmd = connection.CreateCommand();
        cmd.Transaction = tx;
        cmd.CommandText = "DELETE FROM traffic_minute WHERE ts < $before;";
        cmd.With("$before", now - (long)MinuteRetention.TotalMilliseconds);
        await cmd.ExecuteNonQueryAsync(cancellationToken);
    }
}
