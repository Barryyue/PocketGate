using System.Globalization;
using System.Reflection;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging;

namespace NetworkControl.Storage.Sqlite;

/// <summary>
/// 數據庫遷移：按編號順序執行內嵌的 <c>Migrations/&lt;Db&gt;/NNNN_name.sql</c>，
/// 當前版本記錄在 <c>PRAGMA user_version</c> 中。每個腳本在一個事務中執行。
/// 新增遷移：只需添加下一個編號的 SQL 文件，不要修改已發布的腳本。
/// </summary>
internal static partial class Migrator
{
    public static async Task<int> MigrateAsync(SqliteDatabase database, ILogger logger, CancellationToken cancellationToken)
    {
        await using var connection = await database.OpenAsync(cancellationToken);

        // WAL 模式是持久化的數據庫屬性，設置一次即可
        await ExecuteAsync(connection, "PRAGMA journal_mode = WAL;", cancellationToken);

        var current = Convert.ToInt32(await ScalarAsync(connection, "PRAGMA user_version;", cancellationToken), CultureInfo.InvariantCulture);
        var databaseName = System.IO.Path.GetFileName(database.Path);
        var applied = 0;

        foreach (var (version, name) in Discover(database.MigrationResourcePrefix).Where(m => m.Version > current))
        {
            var sql = await ReadResourceAsync(name, cancellationToken);
            await using var tx = (SqliteTransaction)await connection.BeginTransactionAsync(cancellationToken);
            await using (var cmd = connection.CreateCommand())
            {
                cmd.Transaction = tx;
                cmd.CommandText = sql + $"\nPRAGMA user_version = {version.ToString(CultureInfo.InvariantCulture)};";
                await cmd.ExecuteNonQueryAsync(cancellationToken);
            }

            await tx.CommitAsync(cancellationToken);
            LogApplied(logger, databaseName, version, name);
            applied++;
        }

        return applied;
    }

    internal static IEnumerable<(int Version, string ResourceName)> Discover(string prefix)
    {
        return typeof(Migrator).Assembly.GetManifestResourceNames()
            .Where(n => n.StartsWith(prefix, StringComparison.Ordinal) && n.EndsWith(".sql", StringComparison.OrdinalIgnoreCase))
            .Select(n =>
            {
                var file = n[prefix.Length..];
                var number = file[..file.IndexOf('_', StringComparison.Ordinal)];
                return (int.Parse(number, CultureInfo.InvariantCulture), n);
            })
            .OrderBy(m => m.Item1);
    }

    private static async Task<string> ReadResourceAsync(string name, CancellationToken cancellationToken)
    {
        await using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(name)
            ?? throw new InvalidOperationException($"找不到遷移腳本資源 {name}。");
        using var reader = new StreamReader(stream);
        return await reader.ReadToEndAsync(cancellationToken);
    }

    private static async Task ExecuteAsync(SqliteConnection connection, string sql, CancellationToken cancellationToken)
    {
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = sql;
        await cmd.ExecuteNonQueryAsync(cancellationToken);
    }

    private static async Task<object?> ScalarAsync(SqliteConnection connection, string sql, CancellationToken cancellationToken)
    {
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = sql;
        return await cmd.ExecuteScalarAsync(cancellationToken);
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "數據庫 {Database} 已遷移到版本 {Version}（{Script}）")]
    private static partial void LogApplied(ILogger logger, string database, int version, string script);
}
