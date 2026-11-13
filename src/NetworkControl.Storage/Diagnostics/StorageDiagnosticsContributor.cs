using System.Globalization;
using System.Text;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Core.Domain;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Diagnostics;

/// <summary>
/// 存儲部分的診斷信息：數據庫文件大小和遷移版本（總是收集）；
/// config.db 的一致性副本（用戶勾選「包含規則」時）；最近的連接日誌（用戶勾選「包含連接日誌」時）。
/// </summary>
internal sealed class StorageDiagnosticsContributor(
    ConfigDatabase config,
    ActivityDatabase activity,
    IActivityQuery activityQuery,
    TimeProvider time) : IDiagnosticsContributor
{
    private const int MaxActivityRows = 5000;

    private static readonly TimeSpan ActivityWindow = TimeSpan.FromDays(7);

    public string Name => "data";

    public async Task CollectAsync(IDiagnosticsSink sink, DiagnosticsCollectOptions options, CancellationToken cancellationToken)
    {
        var summary = new StringBuilder();
        foreach (var database in new SqliteDatabase[] { config, activity })
        {
            await DescribeAsync(database, summary, cancellationToken);
        }

        sink.AddText("databases.txt", summary.ToString());

        if (options.IncludeConfiguration)
        {
            await AddBackupAsync(sink, config, cancellationToken);
        }

        if (options.IncludeActivity)
        {
            sink.AddText("activity-recent.tsv", await ExportActivityAsync(cancellationToken));
        }
    }

    private static async Task DescribeAsync(SqliteDatabase database, StringBuilder text, CancellationToken cancellationToken)
    {
        var file = new FileInfo(database.Path);
        text.AppendLine(CultureInfo.InvariantCulture, $"[{file.Name}]")
            .AppendLine(CultureInfo.InvariantCulture, $"Path      {file.FullName}")
            .AppendLine(CultureInfo.InvariantCulture, $"Size      {(file.Exists ? file.Length.ToString("N0", CultureInfo.InvariantCulture) + " bytes" : "(missing)")}");
        var wal = new FileInfo(database.Path + "-wal");
        if (wal.Exists)
        {
            text.AppendLine(CultureInfo.InvariantCulture, $"WAL size  {wal.Length:N0} bytes");
        }

        if (file.Exists)
        {
            await using var connection = await database.OpenAsync(cancellationToken);
            await using var command = connection.CreateCommand();
            command.CommandText = "PRAGMA user_version; ";
            text.AppendLine(CultureInfo.InvariantCulture, $"Version   {await command.ExecuteScalarAsync(cancellationToken)}");
            command.CommandText = "PRAGMA integrity_check(5);";
            await using var reader = await command.ExecuteReaderAsync(cancellationToken);
            var results = new List<string>();
            while (await reader.ReadAsync(cancellationToken))
            {
                results.Add(reader.GetString(0));
            }

            text.AppendLine(CultureInfo.InvariantCulture, $"Integrity {string.Join("; ", results)}");
        }

        text.AppendLine();
    }

    /// <summary>用 SQLite 在線備份得到一致的副本（服務正在寫入也不會得到損壞的文件），再寫入診斷包。</summary>
    private static async Task AddBackupAsync(IDiagnosticsSink sink, SqliteDatabase database, CancellationToken cancellationToken)
    {
        var temp = Path.Combine(Path.GetTempPath(), $"networkcontrol-{Guid.NewGuid():N}.db");
        try
        {
            await database.BackupToAsync(temp, cancellationToken);

            await using var stream = new FileStream(temp, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, FileOptions.Asynchronous);
            await sink.AddBinaryAsync(Path.GetFileName(database.Path), stream, cancellationToken);
        }
        finally
        {
            File.Delete(temp);
        }
    }

    private async Task<string> ExportActivityAsync(CancellationToken cancellationToken)
    {
        var entries = await activityQuery.QueryAsync(
            new LogQuery { From = time.GetUtcNow() - ActivityWindow, Limit = MaxActivityRows }, cancellationToken);
        var text = new StringBuilder("first_seen_utc\tlast_seen_utc\tcount\tapp_path\tdirection\tprotocol\tremote\tport\thostname\taction\treason\n");
        foreach (var e in entries)
        {
            text.AppendJoin('\t',
                    e.FirstSeen.UtcDateTime.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture),
                    e.LastSeen.UtcDateTime.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture),
                    e.Count.ToString(CultureInfo.InvariantCulture),
                    e.AppPath ?? "",
                    e.Direction.ToString(),
                    e.Protocol.ToString(),
                    e.RemoteAddress.ToString(),
                    e.RemotePort.ToString(CultureInfo.InvariantCulture),
                    e.Hostname ?? "",
                    e.Verdict.Action == VerdictAction.Allow ? "allow" : "deny",
                    e.Verdict.Reason.ToString())
                .Append('\n');
        }

        return text.ToString();
    }
}
