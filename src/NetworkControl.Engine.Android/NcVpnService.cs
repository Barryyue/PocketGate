using global::Android.App;
using global::Android.Content;
using global::Android.Content.PM;
using global::Android.Net;
using global::Android.OS;
using global::Android.Runtime;

namespace NetworkControl.Engine.Android;

/// <summary>
/// 系統 VPN 服務（docs/android.md §5、§9）。本類只負責與系統交互（建立虛擬網卡、前台通知、撤銷回調），
/// 狀態和決策都在 <see cref="AndroidVpnTunnel"/> 和引擎中。Java 類名固定：「始終開啟的 VPN」設定按組件名記錄。
/// </summary>
[Service(
    Name = JavaName,
    Permission = "android.permission.BIND_VPN_SERVICE",
    Exported = false,
    ForegroundServiceType = ForegroundService.TypeSystemExempted)]
[IntentFilter([ServiceInterface])]
[MetaData("android.net.VpnService.SUPPORTS_ALWAYS_ON", Value = "true")]
public sealed class NcVpnService : VpnService
{
    public const string JavaName = "com.networkcontrol.android.NcVpnService";

    /// <summary>本應用自己啟動服務時使用的 Action；系統（始終開啟的 VPN）啟動時 Action 為 VpnService.SERVICE_INTERFACE 或為空。</summary>
    public const string ActionStart = "com.networkcontrol.android.action.START_VPN";

    public const string SessionName = "NetworkControl";

    private const string Ipv4Address = "10.111.222.1";
    private const string Ipv6Address = "fd00:6e63::1";

    private bool _foreground;

    public NcVpnService()
    {
    }

    private NcVpnService(IntPtr javaReference, JniHandleOwnership transfer)
        : base(javaReference, transfer)
    {
    }

    public override void OnCreate()
    {
        base.OnCreate();
        AndroidVpnTunnel.Current?.OnServiceCreated(this);
    }

    public override StartCommandResult OnStartCommand(Intent? intent, StartCommandFlags flags, int startId)
    {
        if (intent?.Action != ActionStart)
        {
            // 系統啟動（始終開啟的 VPN、進程被殺後的恢復）：讓宿主啟動核心並恢復保護
            AndroidVpnTunnel.Current?.OnSystemStart();
        }

        // 不需要系統在進程被殺後自動重啟服務：始終開啟的 VPN 由系統負責，其他情況由用戶重新打開
        return StartCommandResult.NotSticky;
    }

    public override void OnRevoke()
    {
        AndroidVpnTunnel.Current?.OnRevoked(this);
        base.OnRevoke();
    }

    public override void OnDestroy()
    {
        AndroidVpnTunnel.Current?.OnServiceDestroyed(this);
        base.OnDestroy();
    }

    /// <summary>按配置建立虛擬網卡。用戶未授權時返回 null。</summary>
    internal ParcelFileDescriptor? Establish(Vpn.Platform.VpnTunnelConfig config, PendingIntent? configureIntent, Action<string, Exception> onSkippedPackage)
    {
        using var builder = new Builder(this);
        builder.SetSession(SessionName);
        builder.SetMtu(config.Mtu);
        builder.AddAddress(Ipv4Address, 32);
        builder.AddAddress(Ipv6Address, 128);
        builder.AddRoute("0.0.0.0", 0);
        builder.AddRoute("::", 0);
        // API 29+ 的 VPN 默認視為按流量計費，會讓系統和應用誤以為在用行動網路
        builder.SetMetered(false);
        builder.SetBlocking(false);
        if (configureIntent is not null)
        {
            builder.SetConfigureIntent(configureIntent);
        }

        foreach (var package in config.BypassPackages)
        {
            try
            {
                builder.AddDisallowedApplication(package);
            }
            catch (PackageManager.NameNotFoundException ex)
            {
                // 應用在規劃之後被卸載
                onSkippedPackage(package, ex);
            }
        }

        return builder.Establish();
    }

    /// <summary>成為前台服務（虛擬網卡建立後調用；systemExempted 類型要求應用已獲得 VPN 授權）。</summary>
    internal void EnsureForeground(int notificationId, Notification notification)
    {
        if (_foreground)
        {
            return;
        }

        if (OperatingSystem.IsAndroidVersionAtLeast(34))
        {
            StartForeground(notificationId, notification, ForegroundService.TypeSystemExempted);
        }
        else
        {
            StartForeground(notificationId, notification);
        }

        _foreground = true;
    }

    /// <summary>更新前台通知的內容（例如語言變化）。</summary>
    internal void UpdateForeground(int notificationId, Notification notification)
    {
        if (_foreground && GetSystemService(NotificationService) is NotificationManager manager)
        {
            manager.Notify(notificationId, notification);
        }
    }

    internal void Shutdown()
    {
        if (_foreground)
        {
            StopForeground(StopForegroundFlags.Remove);
            _foreground = false;
        }

        StopSelf();
    }
}
