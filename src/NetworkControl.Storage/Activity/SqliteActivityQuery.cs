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

    private static string EscapeLike(string value) =>
        value.Replace("\\", "\\\\", StringComparison.Ordinal)
            .Replace("%", "\\%", StringComparison.Ordinal)
            .Replace("_", "\\_", StringComparison.Ordinal);
}
