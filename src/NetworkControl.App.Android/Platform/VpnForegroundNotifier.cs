using global::Android.App;
using global::Android.Content;
using NetworkControl.Engine.Android;

namespace NetworkControl.Android.Platform;

/// <summary>VPN 前台服務的常駐通知。</summary>
internal sealed class VpnForegroundNotifier : IVpnForegroundNotifier
{
    public int NotificationId => 1;

    public Notification Build(Context context)
    {
        NotificationChannels.Ensure(context);
        return new Notification.Builder(context, NotificationChannels.Service)
            .SetSmallIcon(Resource.Drawable.ic_stat_shield)
            .SetContentTitle(context.GetString(Resource.String.service_title))
            .SetContentText(context.GetString(Resource.String.service_text))
            .SetContentIntent(NotificationChannels.OpenApp(context))
            .SetOngoing(true)
            .SetShowWhen(false)
            .Build()!;
    }

    public PendingIntent? CreateConfigureIntent(Context context) => NotificationChannels.OpenApp(context);
}
