using System.Globalization;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Backup;

/// <summary>
/// config.db 的每日備份（FR-SET-04）：<c>&lt;數據目錄&gt;/backups/config-yyyyMMdd.db</c>，按本地日期命名，保留最近 7 份。
/// 每天只建立一次，當天已有備份就不再覆蓋，這樣升級當天遷移前的副本不會被遷移後的副本頂掉。
/// 由 <see cref="ConfigBackupStartupTask"/>（遷移前）和 <see cref="ConfigBackupService"/>（之後每小時檢查一次）調用。
/// </summary>
internal sealed partial class ConfigBackup(
    ConfigDatabase config,
    IOptions<StorageOptions> options,
    TimeProvider time,
    ILogger<ConfigBackup> logger)
{
    public const int MaxBackupCount = 7;

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

    [LoggerMessage(Level = LogLevel.Information, Message = "已建立配置備份：{Path}")]
    private static partial void LogBackupCompleted(ILogger logger, string path);

    [LoggerMessage(Level = LogLevel.Error, Message = "建立配置備份失敗：{Path}")]
    private static partial void LogBackupFailed(ILogger logger, Exception exception, string path);

    [LoggerMessage(Level = LogLevel.Warning, Message = "刪除備份文件失敗：{Path}")]
    private static partial void LogDeleteFailed(ILogger logger, Exception exception, string path);
}
