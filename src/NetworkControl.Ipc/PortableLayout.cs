namespace NetworkControl.Ipc;

/// <summary>
/// 可攜版（綠色版）佈局：發布包根目錄下有 <c>portable.txt</c> 標識，界面和服務分別位於 App、Service 子目錄。
/// 安裝版的目錄結構完全相同，因此不能用目錄結構判斷，必須依賴標識文件（安裝程序不會複製它）。
/// </summary>
public static class PortableLayout
{
    public const string MarkerFileName = "portable.txt";

    public const string AppDirectoryName = "App";

    public const string ServiceDirectoryName = "Service";

    public const string DataDirectoryName = "data";

    internal static string AppExecutableName => OperatingSystem.IsWindows() ? "NetworkControl.exe" : "NetworkControl";

    internal static string ServiceExecutableName =>
        OperatingSystem.IsWindows() ? "NetworkControl.Service.exe" : "NetworkControl.Service";

    /// <summary>當前進程是否運行在可攜版佈局中。</summary>
    public static bool IsActive => RootDirectory is not null;

    /// <summary>可攜版根目錄；不是可攜版時為 null。只在進程啟動時計算一次。</summary>
    public static string? RootDirectory { get; } = Detect(AppContext.BaseDirectory);

    /// <summary>可攜版的數據目錄（&lt;根目錄&gt;/data）；不是可攜版時為 null。</summary>
    public static string? DataDirectory =>
        RootDirectory is null ? null : Path.Combine(RootDirectory, DataDirectoryName);

    /// <summary>可攜版中的界面可執行文件；不是可攜版時為 null。</summary>
    public static string? AppExecutable =>
        RootDirectory is null ? null : Path.Combine(RootDirectory, AppDirectoryName, AppExecutableName);

    /// <summary>可攜版中的服務可執行文件；不是可攜版時為 null。</summary>
    public static string? ServiceExecutable =>
        RootDirectory is null ? null : Path.Combine(RootDirectory, ServiceDirectoryName, ServiceExecutableName);

    /// <summary>
    /// 判斷給定目錄（應為 App 或 Service 目錄）是否屬於可攜版佈局。
    /// 必須同時存在標識文件、界面可執行文件和服務可執行文件，避免目錄被移動或只複製了一部分時誤判。
    /// </summary>
    public static string? Detect(string baseDirectory)
    {
        var directory = new DirectoryInfo(Path.GetFullPath(baseDirectory));
        if (!string.Equals(directory.Name, AppDirectoryName, StringComparison.OrdinalIgnoreCase)
            && !string.Equals(directory.Name, ServiceDirectoryName, StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        if (directory.Parent?.FullName is not { } root)
        {
            return null;
        }

        return File.Exists(Path.Combine(root, MarkerFileName))
            && File.Exists(Path.Combine(root, AppDirectoryName, AppExecutableName))
            && File.Exists(Path.Combine(root, ServiceDirectoryName, ServiceExecutableName))
                ? root
                : null;
    }
}
