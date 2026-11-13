using System.Globalization;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Backup;

/// <summary>一份配置備份。<see cref="IsRestoreSnapshot"/> 為 true 表示恢復前自動建立的快照。</summary>
public sealed record ConfigBackupInfo(string Name, DateTime CreatedAt, long Size, bool IsRestoreSnapshot);

/// <summary>
/// config.db 的每日備份（FR-SET-04）：<c>&lt;數據目錄&gt;/backups/config-yyyyMMdd.db</c>，按本地日期命名，保留最近 7 份。
/// 每天只建立一次，當天已有備份就不再覆蓋，這樣升級當天遷移前的副本不會被遷移後的副本頂掉。
/// 由 <see cref="ConfigBackupStartupTask"/>（遷移前）和 <see cref="ConfigBackupService"/>（之後每小時檢查一次）調用。
/// </summary>
public sealed partial class ConfigBackup(
    ConfigDatabase config,
    IOptions<StorageOptions> options,
    TimeProvider time,
    ILogger<ConfigBackup> logger)
{
    public const int MaxBackupCount = 7;

    public const int MaxSnapshotCount = 3;

    private const string DirectoryName = "backups";

    public string BackupDirectory => Path.Combine(options.Value.ResolveDataDirectory(), DirectoryName);

    /// <summary>
    /// 當天還沒有備份時建立一份，並清理多餘的舊備份。返回是否新建了備份。
    /// 失敗只記錄日誌，不拋出（取消除外）：備份失敗不能影響服務啟動和運行。
    /// </summary>
    public async Task<bool> EnsureTodayAsync(CancellationToken cancellationToken)
    {
        if (!File.Exists(config.Path))
        {
            // 首次安裝：數據庫還沒建立，沒有可備份的內容
            return false;
        }

        var date = time.GetLocalNow().ToString("yyyyMMdd", CultureInfo.InvariantCulture);
        var destination = Path.Combine(BackupDirectory, $"config-{date}.db");
        if (File.Exists(destination))
        {
            return false;
        }

        var temporary = $"{destination}.{Guid.NewGuid():N}.tmp";
        try
        {
            Directory.CreateDirectory(BackupDirectory);
            await config.BackupToAsync(temporary, cancellationToken);

            // 先寫臨時文件再改名：中途失敗不會留下殘缺的正式備份
            File.Move(temporary, destination, overwrite: false);
            LogBackupCompleted(logger, destination);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            LogBackupFailed(logger, ex, destination);
            return false;
        }
        finally
        {
            TryDelete(temporary);
        }

        Cleanup();
        return true;
    }

    /// <summary>列出每日備份和恢復前快照，最新的在前。</summary>
    public IReadOnlyList<ConfigBackupInfo> List()
    {
        if (!Directory.Exists(BackupDirectory))
        {
            return [];
        }

        return Directory.EnumerateFiles(BackupDirectory, "config-*.db")
            .Select(path => new FileInfo(path))
            .Where(file => IsManagedName(file.Name))
            .Select(file => new ConfigBackupInfo(file.Name, file.LastWriteTimeUtc, file.Length, SnapshotFileName().IsMatch(file.Name)))
            .OrderByDescending(info => info.CreatedAt)
            .ToArray();
    }

    /// <summary>
    /// 讀取備份中的規則。備份可能是舊版本的結構：先複製到臨時文件並執行遷移，再用正常的倉儲讀取，
    /// 不會修改備份本身。
    /// </summary>
    /// <exception cref="FileNotFoundException">名稱不是本類管理的備份，或文件不存在。</exception>
    public async Task<IReadOnlyList<Core.Domain.Rule>> LoadRulesAsync(string name, CancellationToken cancellationToken)
    {
        // 只接受本類生成的文件名，防止通過名稱讀取任意路徑
        var source = Path.Combine(BackupDirectory, name);
        if (!IsManagedName(name) || !File.Exists(source))
        {
            throw new FileNotFoundException("備份不存在。", name);
        }

        var directory = Path.Combine(Path.GetTempPath(), "NetworkControl", "restore-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            File.Copy(source, Path.Combine(directory, "config.db"));
            var copy = new ConfigDatabase(Options.Create(new StorageOptions { DataDirectory = directory }));
            await Migrator.MigrateAsync(copy, logger, cancellationToken);
            var stored = await new Repositories.SqliteRuleRepository(copy).LoadAllAsync(cancellationToken);
            return stored.Select(s => s.Rule).ToArray();
        }
        finally
        {
            // 連接池會保持臨時文件打開，刪除前先釋放
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            try
            {
                Directory.Delete(directory, recursive: true);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                LogDeleteFailed(logger, ex, directory);
            }
        }
    }

    /// <summary>恢復前為當前數據庫建立快照（保留最近 3 份），恢復錯了還能再恢復回來。</summary>
    public async Task<string> CreateRestoreSnapshotAsync(CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(BackupDirectory);
        var name = $"config-restore-{time.GetLocalNow().ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture)}.db";
        var destination = Path.Combine(BackupDirectory, name);
        var temporary = $"{destination}.{Guid.NewGuid():N}.tmp";
        try
        {
            await config.BackupToAsync(temporary, cancellationToken);
            File.Move(temporary, destination, overwrite: true);
        }
        finally
        {
            TryDelete(temporary);
        }

        foreach (var expired in Directory.EnumerateFiles(BackupDirectory, "config-restore-*.db")
                     .Where(path => SnapshotFileName().IsMatch(Path.GetFileName(path)))
                     .OrderByDescending(path => Path.GetFileName(path), StringComparer.Ordinal)
                     .Skip(MaxSnapshotCount))
        {
            TryDelete(expired);
        }

        LogBackupCompleted(logger, destination);
        return name;
    }

    private static bool IsManagedName(string name) => BackupFileName().IsMatch(name) || SnapshotFileName().IsMatch(name);

    /// <summary>只刪除本類命名格式的備份，用戶手動放進目錄的文件不受影響。</summary>
    private void Cleanup()
    {
        var expired = Directory.EnumerateFiles(BackupDirectory, "config-*.db")
            .Where(path => BackupFileName().IsMatch(Path.GetFileName(path)))
            .OrderByDescending(path => Path.GetFileName(path), StringComparer.Ordinal)
            .Skip(MaxBackupCount);

        foreach (var path in expired)
        {
            TryDelete(path);
        }
    }

    private void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            LogDeleteFailed(logger, ex, path);
        }
    }

    [GeneratedRegex(@"^config-\d{8}\.db$", RegexOptions.CultureInvariant)]
    private static partial Regex BackupFileName();

    [GeneratedRegex(@"^config-restore-\d{8}-\d{6}\.db$", RegexOptions.CultureInvariant)]
    private static partial Regex SnapshotFileName();

    [LoggerMessage(Level = LogLevel.Information, Message = "已建立配置備份：{Path}")]
    private static partial void LogBackupCompleted(ILogger logger, string path);

    [LoggerMessage(Level = LogLevel.Error, Message = "建立配置備份失敗：{Path}")]
    private static partial void LogBackupFailed(ILogger logger, Exception exception, string path);

    [LoggerMessage(Level = LogLevel.Warning, Message = "刪除備份文件失敗：{Path}")]
    private static partial void LogDeleteFailed(ILogger logger, Exception exception, string path);
}
