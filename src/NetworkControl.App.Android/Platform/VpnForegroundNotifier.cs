using global::Android.App;
using global::Android.Content;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Engine.Android;

namespace NetworkControl.Android.Platform;

/// <summary>VPN 前台服務的常駐通知（文本來自 AtomUI Catalog，跟隨應用內語言）。</summary>
internal sealed class VpnForegroundNotifier : IVpnForegroundNotifier
{
    public int NotificationId => 1;

    public Notification Build(Context context)
    {
        NotificationChannels.Ensure(context);
        var text = NotificationText.Current;
        return new Notification.Builder(context, NotificationChannels.Service)
            .SetSmallIcon(Resource.Drawable.ic_stat_shield)
            .SetContentTitle(text.Get(context, OverviewLangResourceKind.StatusOn))
            .SetContentText(text.Get(context, OverviewLangResourceKind.HintOn))
            .SetContentIntent(NotificationChannels.OpenApp(context))
            .SetOngoing(true)
            .SetShowWhen(false)
            .Build()!;
    }

    public PendingIntent? CreateConfigureIntent(Context context) => NotificationChannels.OpenApp(context);
}
