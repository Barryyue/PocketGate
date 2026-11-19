using AndroidX.Core.Content;
using global::Android.App;
using global::Android.Content;
using global::Android.Graphics;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Engine.Android;

namespace NetworkControl.Android.Platform;

/// <summary>VPN 前台服務的常駐通知（帶極簡護盾小圖示與 PocketGate 彩色大圖示）。</summary>
internal sealed class VpnForegroundNotifier : IVpnForegroundNotifier
{
    private Bitmap? _largeIcon;

    public int NotificationId => 1;

    public Notification Build(Context context)
    {
        NotificationChannels.Ensure(context);
        var text = NotificationText.Current;
        var builder = new Notification.Builder(context, NotificationChannels.Service)
            .SetSmallIcon(Resource.Drawable.ic_stat_shield)
            .SetContentTitle(text.Get(context, OverviewLangResourceKind.StatusOn))
            .SetContentText(text.Get(context, OverviewLangResourceKind.HintOn))
            .SetContentIntent(NotificationChannels.OpenApp(context))
            .SetOngoing(true)
            .SetShowWhen(false);

        if (GetLargeIcon(context) is { } large)
        {
            builder.SetLargeIcon(large);
        }

        return builder.Build()!;
    }

    public PendingIntent? CreateConfigureIntent(Context context) => NotificationChannels.OpenApp(context);

    private Bitmap? GetLargeIcon(Context context)
    {
        if (_largeIcon is not null) return _largeIcon;
        try
        {
            var drawable = ContextCompat.GetDrawable(context, Resource.Mipmap.ic_launcher);
            if (drawable is null) return null;
            const int size = 96;
            var bmp = Bitmap.CreateBitmap(size, size, Bitmap.Config.Argb8888!);
            using var canvas = new Canvas(bmp);
            drawable.SetBounds(0, 0, size, size);
            drawable.Draw(canvas);
            _largeIcon = bmp;
            return bmp;
        }
        catch
        {
            return null;
        }
    }
}
