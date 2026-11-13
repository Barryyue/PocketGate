using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Options;

namespace NetworkControl.Storage.Sqlite;

/// <summary>一個 SQLite 數據庫文件。每次操作打開一個連接（底層連接池複用），用完即釋放。</summary>
public abstract class SqliteDatabase
{
    private readonly string _connectionString;

    protected SqliteDatabase(string path, string migrationResourcePrefix)
    {
        Path = path;
        MigrationResourcePrefix = migrationResourcePrefix;
        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = true,
            ForeignKeys = true,
            DefaultTimeout = 5,
        }.ToString();
    }

    public string Path { get; }

    internal string MigrationResourcePrefix { get; }

    public async Task<SqliteConnection> OpenAsync(CancellationToken cancellationToken)
    {
        var connection = new SqliteConnection(_connectionString);
        try
        {
            await connection.OpenAsync(cancellationToken);
            return connection;
        }
        catch
        {
            await connection.DisposeAsync();
            throw;
        }
    }

    /// <summary>
    /// 用 SQLite 在線備份 API 把數據庫完整複製到 <paramref name="destination"/>（覆蓋已有內容）。
    /// 備份期間其他連接可以繼續讀寫，得到的副本是一致的。
    /// </summary>
    public async Task BackupToAsync(string destination, CancellationToken cancellationToken)
    {
        await using var source = await OpenAsync(cancellationToken);
        await using var target = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = destination,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = false,
        }.ToString());
        await target.OpenAsync(cancellationToken);
        source.BackupDatabase(target);
    }
}

/// <summary>config.db：規則、應用、配置文件、設置。體積小、關鍵，需要備份。</summary>
public sealed class ConfigDatabase(IOptions<StorageOptions> options)
    : SqliteDatabase(
        System.IO.Path.Combine(options.Value.ResolveDataDirectory(), options.Value.ConfigDatabaseFileName),
        "NetworkControl.Storage.Migrations.Config.");

/// <summary>activity.db：日誌、統計。體積大、可重建。</summary>
public sealed class ActivityDatabase(IOptions<StorageOptions> options)
    : SqliteDatabase(
        System.IO.Path.Combine(options.Value.ResolveDataDirectory(), options.Value.ActivityDatabaseFileName),
        "NetworkControl.Storage.Migrations.Activity.");
