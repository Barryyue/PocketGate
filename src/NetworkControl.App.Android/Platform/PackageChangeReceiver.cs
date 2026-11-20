using global::Android.App;
using global::Android.Content;
using Microsoft.Extensions.DependencyInjection;
using NetworkControl.Android.Hosting;

namespace NetworkControl.Android.Platform;

/// <summary>
/// 監聽新應用安裝：發出通知提醒使用者，並協助提醒檢查聯網規則。
/// </summary>
[BroadcastReceiver(Enabled = true, Exported = true)]
[IntentFilter([Intent.ActionPackageAdded], DataScheme = "package")]
public sealed class PackageChangeReceiver : BroadcastReceiver
{
    public override void OnReceive(Context? context, Intent? intent)
    {
        if (context is null || intent?.Action != Intent.ActionPackageAdded) return;
        if (intent.GetBooleanExtra(Intent.ExtraReplacing, false)) return;

        var pkg = intent.Data?.SchemeSpecificPart;
        if (string.IsNullOrWhiteSpace(pkg) || string.Equals(pkg, context.PackageName, StringComparison.Ordinal)) return;

        var backend = BackendHost.Current?.Services.GetService<IBackend>();
        if (backend is null) return;

        try
        {
            var pm = context.PackageManager;
            var label = pm?.GetApplicationInfo(pkg, 0)?.LoadLabel(pm) ?? pkg;
            NotificationChannels.Ensure(context);
            if (context.GetSystemService(Context.NotificationService) is NotificationManager manager)
            {
                var open = NotificationChannels.OpenApp(context, tab: 1);
                var notification = new Notification.Builder(context, NotificationChannels.Alerts)
                    .SetSmallIcon(Resource.Drawable.ic_stat_shield)
                    .SetContentTitle(label)
                    .SetContentText(pkg)
                    .SetContentIntent(open)
                    .SetAutoCancel(true)
                    .Build();
                if (notification is not null)
                {
                    manager.Notify(3000 + (pkg.GetHashCode() & 0x0FFF), notification);
                }
            }
        }
        catch
        {
        }
    }
}
