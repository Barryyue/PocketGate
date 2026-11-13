using System.Net;
using System.Text;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Domain;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Activity;

internal sealed class SqliteActivityQuery(ActivityDatabase db) : IActivityQuery
{
    public async Task<IReadOnlyList<LogEntry>> QueryAsync(LogQuery query, CancellationToken cancellationToken)
    {
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var cmd = connection.CreateCommand();

        var sql = new StringBuilder("""
            SELECT id, first_ts, last_ts, app_id, app_path, app_name, direction, protocol, remote_ip, remote_port,
                   hostname, verdict, reason, rule_id, count
            FROM connection_log WHERE 1 = 1
            """);

        if (query.From is { } from)
        {
            sql.Append(" AND last_ts >= $from");
            cmd.With("$from", from.ToUnixMs());
        }

        if (query.To is { } to)
        {
            sql.Append(" AND first_ts <= $to");
            cmd.With("$to", to.ToUnixMs());
        }

        if (query.AppId is { } appId)
        {
            sql.Append(" AND app_id = $app");
            cmd.With("$app", appId);
        }

        if (query.Action is { } action)
        {
            sql.Append(" AND verdict = $verdict");
            cmd.With("$verdict", (int)action);
        }

        if (!string.IsNullOrWhiteSpace(query.Keyword))
        {
            sql.Append(" AND (app_path LIKE $kw ESCAPE '\\' OR app_name LIKE $kw ESCAPE '\\' OR hostname LIKE $kw ESCAPE '\\' OR remote_ip LIKE $kw ESCAPE '\\')");
            cmd.With("$kw", "%" + EscapeLike(query.Keyword.Trim()) + "%");
        }

        if (query.BeforeId is { } beforeId)
        {
            sql.Append(" AND id < $before");
            cmd.With("$before", beforeId);
        }

        sql.Append(" ORDER BY id DESC LIMIT $limit;");
        cmd.With("$limit", Math.Clamp(query.Limit, 1, 5000));
        cmd.CommandText = sql.ToString();

        var result = new List<LogEntry>();
        await using var r = await cmd.ExecuteReaderAsync(cancellationToken);
        while (await r.ReadAsync(cancellationToken))
        {
            var appIdValue = r.GetInt64(3);
            result.Add(new LogEntry(
                r.GetInt64(0),
                SqliteExtensions.FromUnixMs(r.GetInt64(1)),
                SqliteExtensions.FromUnixMs(r.GetInt64(2)),
                appIdValue < 0 ? null : appIdValue,
                r.GetNullableString(4),
                r.GetNullableString(5),
                (Direction)r.GetInt32(6),
                (L4Protocol)r.GetInt32(7),
                IPAddress.Parse(r.GetString(8)),
                r.GetInt32(9),
                r.GetNullableString(10),
                new Verdict((VerdictAction)r.GetInt32(11), (VerdictReason)r.GetInt32(12), r.GetNullableString(13) is { } rule ? Guid.Parse(rule) : null),
                r.GetInt32(14)));
        }

        return result;
    }

    public async Task<ActivitySummary> SummarizeAsync(ActivitySummaryQuery query, CancellationToken cancellationToken)
    {
        var bucketMs = Math.Max(60_000L, (long)query.BucketSize.TotalMilliseconds);
        var fromMs = query.From.ToUnixMs() / 60_000 * 60_000;
        var toMs = Math.Max(fromMs, query.To.ToUnixMs());
        var bucketCount = (int)Math.Min(10_000, (toMs - fromMs + bucketMs - 1) / bucketMs);
        var top = Math.Clamp(query.TopCount, 0, 100);

        await using var connection = await db.OpenAsync(cancellationToken);

        // 時間軸（同時得出總數）：日誌按分鐘聚合，按 minute_ts 分桶
        var allowed = new long[bucketCount];
        var blocked = new long[bucketCount];
        await using (var cmd = connection.CreateCommand())
        {
            cmd.CommandText = """
                SELECT (minute_ts - $from) / $bucket AS b, verdict, SUM(count)
                FROM connection_log
                WHERE minute_ts >= $from AND minute_ts < $to
                GROUP BY b, verdict;
                """;
            cmd.With("$from", fromMs).With("$to", toMs).With("$bucket", bucketMs);
            await using var r = await cmd.ExecuteReaderAsync(cancellationToken);
            while (await r.ReadAsync(cancellationToken))
            {
                var index = r.GetInt64(0);
                if (index < 0 || index >= bucketCount)
                {
                    continue;
                }

                (r.GetInt32(1) == (int)VerdictAction.Allow ? allowed : blocked)[index] += r.GetInt64(2);
            }
        }

        var timeline = new ActivityBucket[bucketCount];
        for (var i = 0; i < bucketCount; i++)
        {
            timeline[i] = new ActivityBucket(SqliteExtensions.FromUnixMs(fromMs + (i * bucketMs)), allowed[i], blocked[i]);
        }

        int appCount;
        int destinationCount;
        await using (var cmd = connection.CreateCommand())
        {
            cmd.CommandText = """
                SELECT COUNT(DISTINCT app_id || ':' || CASE WHEN app_id < 0 THEN COALESCE(app_path, '') ELSE '' END),
                       COUNT(DISTINCT COALESCE(hostname, remote_ip))
                FROM connection_log
                WHERE minute_ts >= $from AND minute_ts < $to;
                """;
            cmd.With("$from", fromMs).With("$to", toMs);
            await using var r = await cmd.ExecuteReaderAsync(cancellationToken);
            await r.ReadAsync(cancellationToken);
            appCount = r.GetInt32(0);
            destinationCount = r.GetInt32(1);
        }

        // 已識別的應用按 app_id 合併；未識別的（app_id = -1）按路徑區分
        var topApps = await QueryTopAsync(
            connection,
            """
            SELECT app_id, CASE WHEN app_id < 0 THEN COALESCE(app_path, '') ELSE '' END AS k,
                   MAX(app_name), MAX(app_path),
                   SUM(CASE WHEN verdict = 0 THEN count ELSE 0 END), SUM(CASE WHEN verdict = 0 THEN 0 ELSE count END)
            FROM connection_log
            WHERE minute_ts >= $from AND minute_ts < $to
            GROUP BY app_id, k
            ORDER BY SUM(count) DESC
            LIMIT $top;
            """,
            fromMs,
            toMs,
            top,
            r =>
            {
                var appId = r.GetInt64(0);
                var name = r.GetNullableString(2);
                var path = r.GetNullableString(3);
                var label = !string.IsNullOrWhiteSpace(name) ? name : path;
                return new ActivityTopItem(appId >= 0 ? $"app:{appId}" : $"path:{path}", label, appId >= 0 ? appId : null, r.GetInt64(4), r.GetInt64(5), path);
            },
            cancellationToken);

        var topDestinations = await QueryTopAsync(
            connection,
            """
            SELECT COALESCE(hostname, remote_ip) AS d, 0, NULL, NULL,
                   SUM(CASE WHEN verdict = 0 THEN count ELSE 0 END), SUM(CASE WHEN verdict = 0 THEN 0 ELSE count END)
            FROM connection_log
            WHERE minute_ts >= $from AND minute_ts < $to
            GROUP BY d
            ORDER BY SUM(count) DESC
            LIMIT $top;
            """,
            fromMs,
            toMs,
            top,
            r => new ActivityTopItem(r.GetString(0), r.GetString(0), null, r.GetInt64(4), r.GetInt64(5)),
            cancellationToken);

        return new ActivitySummary(allowed.Sum(), blocked.Sum(), appCount, destinationCount, timeline, topApps, topDestinations);
    }

    private static async Task<IReadOnlyList<ActivityTopItem>> QueryTopAsync(
        Microsoft.Data.Sqlite.SqliteConnection connection,
        string sql,
        long fromMs,
        long toMs,
        int top,
        Func<Microsoft.Data.Sqlite.SqliteDataReader, ActivityTopItem> map,
        CancellationToken cancellationToken)
    {
        if (top == 0)
        {
            return [];
        }

        await using var cmd = connection.CreateCommand();
        cmd.CommandText = sql;
        cmd.With("$from", fromMs).With("$to", toMs).With("$top", top);
        var result = new List<ActivityTopItem>();
        await using var r = await cmd.ExecuteReaderAsync(cancellationToken);
        while (await r.ReadAsync(cancellationToken))
        {
            result.Add(map(r));
        }

        return result;
    }

    private static string EscapeLike(string value) =>
        value.Replace("\\", "\\\\", StringComparison.Ordinal)
            .Replace("%", "\\%", StringComparison.Ordinal)
            .Replace("_", "\\_", StringComparison.Ordinal);
}
