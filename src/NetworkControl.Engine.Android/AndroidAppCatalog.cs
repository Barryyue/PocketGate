using System.Security.Cryptography;
using global::Android.Content;
using global::Android.Content.PM;
using Microsoft.Extensions.Logging;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Android;

/// <summary>
/// 基於 PackageManager 的已安裝應用目錄：只列出申請了 INTERNET 權限的應用；
/// 監聽軟件包安裝、卸載、更新廣播，變化時重新加載並觸發 <see cref="Changed"/>。
/// </summary>
public sealed partial class AndroidAppCatalog : IInstalledAppCatalog, IDisposable
{
    private const string InternetPermission = "android.permission.INTERNET";

    private readonly Context _context;
    private readonly ILogger<AndroidAppCatalog> _logger;
    private readonly Lock _gate = new();
    private readonly PackageChangeReceiver _receiver;
    private Snapshot? _snapshot;

    public AndroidAppCatalog(Context context, ILogger<AndroidAppCatalog> logger)
    {
        _context = context.ApplicationContext ?? context;
        _logger = logger;
        _receiver = new PackageChangeReceiver(this);

        var filter = new IntentFilter();
        filter.AddAction(Intent.ActionPackageAdded);
        filter.AddAction(Intent.ActionPackageRemoved);
        filter.AddAction(Intent.ActionPackageReplaced);
        filter.AddAction(Intent.ActionPackageChanged);
        filter.AddDataScheme("package");
        if (OperatingSystem.IsAndroidVersionAtLeast(33))
        {
            _context.RegisterReceiver(_receiver, filter, ReceiverFlags.NotExported);
        }
        else
        {
            _context.RegisterReceiver(_receiver, filter);
        }
    }

    public event EventHandler? Changed;

    public IReadOnlyList<InstalledApp> GetApps() => Current.Apps;

    public IReadOnlyList<string> GetPackagesForUid(int uid)
    {
        if (Current.ByUid.TryGetValue(uid, out var packages))
        {
            return packages;
        }

        // 目錄中沒有（例如沒有 INTERNET 權限的共享 UID 包）：直接問系統
        return _context.PackageManager?.GetPackagesForUid(uid) is { } names ? [.. names.Order(StringComparer.Ordinal)] : [];
    }

    public InstalledApp? Find(string packageName) => Current.ByPackage.GetValueOrDefault(packageName);

    public void Dispose()
    {
        try
        {
            _context.UnregisterReceiver(_receiver);
        }
        catch (Java.Lang.IllegalArgumentException)
        {
            // 未註冊
        }

        _receiver.Dispose();
    }

    /// <summary>丟棄緩存，下次訪問時重新加載。</summary>
    public void Invalidate()
    {
        lock (_gate)
        {
            _snapshot = null;
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    private Snapshot Current
    {
        get
        {
            lock (_gate)
            {
                return _snapshot ??= Load();
            }
        }
    }

    private Snapshot Load()
    {
        var pm = _context.PackageManager ?? throw new InvalidOperationException("PackageManager 不可用。");
        IList<PackageInfo> packages;
        if (OperatingSystem.IsAndroidVersionAtLeast(33))
        {
            packages = pm.GetInstalledPackages(PackageManager.PackageInfoFlags.Of((long)(PackageInfoFlags.Permissions | PackageInfoFlags.SigningCertificates)));
        }
        else
        {
            packages = pm.GetInstalledPackages(PackageInfoFlags.Permissions | PackageInfoFlags.SigningCertificates);
        }

        var apps = new List<InstalledApp>(packages.Count);
        foreach (var info in packages)
        {
            if (info.PackageName is not { } name || info.ApplicationInfo is not { } appInfo)
            {
                continue;
            }

            if (info.RequestedPermissions is not { } permissions || !permissions.Contains(InternetPermission))
            {
                continue;
            }

            string label;
            try
            {
                label = appInfo.LoadLabel(pm) ?? name;
            }
            catch (Java.Lang.Exception)
            {
                label = name;
            }

            apps.Add(new InstalledApp(
                name,
                appInfo.Uid,
                string.IsNullOrWhiteSpace(label) ? name : label,
                (appInfo.Flags & ApplicationInfoFlags.System) != 0,
                info.LongVersionCode,
                DateTimeOffset.FromUnixTimeMilliseconds(info.LastUpdateTime),
                SignerOf(info)));
        }

        LogLoaded(_logger, apps.Count, packages.Count);
        return new Snapshot(apps);
    }

    /// <summary>當前 APK 的簽名證書 SHA-256（多個簽名者時排序後以逗號連接）。密鑰輪換後的應用會表現為簽名變化（見 docs/android.md §14）。</summary>
    private static string? SignerOf(PackageInfo info)
    {
        var signers = info.SigningInfo?.GetApkContentsSigners();
        if (signers is null || signers.Length == 0)
        {
            return null;
        }

        return string.Join(',', signers
            .Select(s => s.ToByteArray())
            .Where(b => b is not null)
            .Select(b => Convert.ToHexStringLower(SHA256.HashData(b!)))
            .Order(StringComparer.Ordinal));
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "已加載 {Count} 個聯網應用（共 {Total} 個軟件包）")]
    private static partial void LogLoaded(ILogger logger, int count, int total);

    private sealed class Snapshot
    {
        public Snapshot(List<InstalledApp> apps)
        {
            Apps = apps;
            ByPackage = apps.ToDictionary(a => a.PackageName, StringComparer.Ordinal);
            ByUid = apps.GroupBy(a => a.Uid).ToDictionary(g => g.Key, g => (IReadOnlyList<string>)[.. g.Select(a => a.PackageName).Order(StringComparer.Ordinal)]);
        }

        public IReadOnlyList<InstalledApp> Apps { get; }

        public Dictionary<string, InstalledApp> ByPackage { get; }

        public Dictionary<int, IReadOnlyList<string>> ByUid { get; }
    }

    private sealed class PackageChangeReceiver(AndroidAppCatalog owner) : BroadcastReceiver
    {
        public override void OnReceive(Context? context, Intent? intent) => owner.Invalidate();
    }
}
