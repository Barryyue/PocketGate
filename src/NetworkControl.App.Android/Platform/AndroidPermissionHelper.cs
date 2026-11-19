using AndroidX.Core.App;
using AndroidX.Core.Content;
using global::Android;
using global::Android.Content;
using global::Android.Content.PM;
using global::Android.Net;
using global::Android.OS;
using global::Android.Provider;
using Uri = global::Android.Net.Uri;

namespace NetworkControl.Android.Platform;

public sealed record PermissionHealthSnapshot(
    bool VpnPrepared,
    bool NotificationsOk,
    bool UsageAccessOk,
    bool AppListOk,
    bool OverlayOk,
    bool BatteryOptimizationOk)
{
    public int ReadyCount =>
        (VpnPrepared ? 1 : 0) +
        (NotificationsOk ? 1 : 0) +
        (UsageAccessOk ? 1 : 0) +
        (AppListOk ? 1 : 0) +
        (OverlayOk ? 1 : 0) +
        (BatteryOptimizationOk ? 1 : 0);

    public int MissingGuideCount =>
        (NotificationsOk ? 0 : 1) +
        (UsageAccessOk ? 0 : 1) +
        (AppListOk ? 0 : 1) +
        (OverlayOk ? 0 : 1) +
        (BatteryOptimizationOk ? 0 : 1);
}

/// <summary>6 項核心權限體檢與系統設定頁跳轉工具（與 Kotlin 版 PermissionHealth 對齊）。</summary>
public static class AndroidPermissionHelper
{
    public static PermissionHealthSnapshot Check(Context context, bool hasTrafficAccess, bool appListOk)
    {
        var vpnOk = global::Android.Net.VpnService.Prepare(context) is null;
        var notifOk = !OperatingSystem.IsAndroidVersionAtLeast(33) ||
                      ContextCompat.CheckSelfPermission(context, Manifest.Permission.PostNotifications) == Permission.Granted;
        notifOk = notifOk && (NotificationManagerCompat.From(context)?.AreNotificationsEnabled() ?? false);
        var overlayOk = Settings.CanDrawOverlays(context);
        var pm = context.GetSystemService(Context.PowerService) as PowerManager;
        var batteryOk = pm?.IsIgnoringBatteryOptimizations(context.PackageName) ?? true;

        return new PermissionHealthSnapshot(vpnOk, notifOk, hasTrafficAccess, appListOk, overlayOk, batteryOk);
    }

    public static void OpenAppSettings(Context context) => StartNewTask(context, new Intent(Settings.ActionApplicationDetailsSettings, Uri.Parse($"package:{context.PackageName}")));

    public static void OpenNotificationSettings(Context context) => StartNewTask(context, new Intent(Settings.ActionAppNotificationSettings).PutExtra(Settings.ExtraAppPackage, context.PackageName));

    public static void OpenOverlaySettings(Context context) => StartNewTask(context, new Intent(Settings.ActionManageOverlayPermission, Uri.Parse($"package:{context.PackageName}")));

    public static void OpenBatteryOptimizationSettings(Context context) => StartNewTask(context, new Intent(Settings.ActionIgnoreBatteryOptimizationSettings));

    public static void OpenVpnSettings(Context context) => StartNewTask(context, new Intent(Settings.ActionVpnSettings));

    private static void StartNewTask(Context context, Intent intent)
    {
        try
        {
            intent.AddFlags(ActivityFlags.NewTask);
            context.StartActivity(intent);
        }
        catch
        {
            try
            {
                context.StartActivity(new Intent(Settings.ActionSettings).AddFlags(ActivityFlags.NewTask));
            }
            catch
            {
            }
        }
    }
}
