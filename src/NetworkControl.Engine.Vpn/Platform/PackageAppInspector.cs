using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;

namespace NetworkControl.Engine.Vpn.Platform;

/// <summary>
/// 把 Android 應用目錄適配為 Core 的 <see cref="IAppInspector"/>：應用名稱、簽名者、文件戳都來自 PackageManager（docs/android.md §5.3）。
/// </summary>
public sealed class PackageAppInspector(IInstalledAppCatalog catalog) : IAppInspector
{
    public FileStamp? GetStamp(string path) =>
        PackageAppPath.TryGetPackage(path, out var package) && catalog.Find(package) is { } app ? ToStamp(app) : null;

    public ValueTask<AppInspection> InspectAsync(string path, CancellationToken cancellationToken)
    {
        if (PackageAppPath.TryGetPackage(path, out var package) && catalog.Find(package) is { } app)
        {
            return ValueTask.FromResult(new AppInspection(ToStamp(app), app.Label, ToSignature(app), Sha256: null));
        }

        // 已卸載的應用或查不到包名的 UID：仍然生成身份，便於記錄和詢問
        return ValueTask.FromResult(new AppInspection(new FileStamp(0, 0), package ?? path, Signature: null, Sha256: null));
    }

    public static CodeSignature? ToSignature(InstalledApp app)
    {
        ArgumentNullException.ThrowIfNull(app);
        return string.IsNullOrEmpty(app.SignerSha256)
            ? null
            : new CodeSignature(app.SignerSha256, SigningId: app.PackageName, IsPlatformBinary: app.IsSystem);
    }

    private static FileStamp ToStamp(InstalledApp app) => new(app.VersionCode, app.LastUpdated.UtcTicks);
}

/// <summary>Android 上沒有可用的進程信息：連接只帶 UID。</summary>
public sealed class NoProcessInfoProvider : IProcessInfoProvider, IProcessExitWatcher
{
    public bool TryGetProcess(int pid, out ProcessSnapshot snapshot)
    {
        snapshot = null!;
        return false;
    }

    public bool IsAlive(ProcessRef process) => false;

    public IDisposable Watch(ProcessRef process, Action onExit) => NullDisposable.Instance;

    private sealed class NullDisposable : IDisposable
    {
        public static readonly NullDisposable Instance = new();

        public void Dispose()
        {
        }
    }
}
