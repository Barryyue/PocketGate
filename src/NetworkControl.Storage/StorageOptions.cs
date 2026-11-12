namespace NetworkControl.Storage;

/// <summary>存儲配置（配置節點 NetworkControl:Storage）。</summary>
public sealed class StorageOptions
{
    /// <summary>
    /// 數據目錄。為空時使用平台默認位置：
    /// Windows %ProgramData%\NetworkControl，Linux /var/lib/netcontrol，macOS /Library/Application Support/NetworkControl。
    /// </summary>
    public string? DataDirectory { get; set; }

    public string ConfigDatabaseFileName { get; set; } = "config.db";

    public string ActivityDatabaseFileName { get; set; } = "activity.db";

    /// <summary>活動日誌批量寫入的間隔。</summary>
    public TimeSpan ActivityFlushInterval { get; set; } = TimeSpan.FromSeconds(1);

    /// <summary>活動日誌寫入隊列容量；隊列滿時丟棄新事件（不能阻塞判決管線）。</summary>
    public int ActivityQueueCapacity { get; set; } = 100_000;

    public static string GetDefaultDataDirectory()
    {
        if (OperatingSystem.IsWindows())
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "NetworkControl");
        }

        return OperatingSystem.IsMacOS()
            ? "/Library/Application Support/NetworkControl"
            : "/var/lib/netcontrol";
    }

    public string ResolveDataDirectory() =>
        Path.GetFullPath(string.IsNullOrWhiteSpace(DataDirectory) ? GetDefaultDataDirectory() : DataDirectory);

    /// <summary>
    /// 解析配置中的數據目錄：為空時使用平台默認位置，相對路徑相對於內容根目錄（而不是進程的當前工作目錄）。
    /// 在依賴注入容器建立之前也可以調用（例如日誌需要提前知道日誌目錄）。
    /// </summary>
    public static string ResolveDataDirectory(string? configured, string contentRoot) =>
        string.IsNullOrWhiteSpace(configured) ? Path.GetFullPath(GetDefaultDataDirectory())
        : Path.IsPathRooted(configured) ? Path.GetFullPath(configured)
        : Path.GetFullPath(Path.Combine(contentRoot, configured));
}
