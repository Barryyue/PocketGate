using AndroidX.Core.Content;
using global::Android.App;
using global::Android.Content;
using global::Android.Graphics;
using global::Android.OS;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Engine.Android;

namespace NetworkControl.Android.Platform;

/// <summary>VPN 前台服務的常駐通知（支援 Android 16 原生實況膠囊 Live Updates 與極簡護盾圖示）。</summary>
internal sealed class VpnForegroundNotifier : IVpnForegroundNotifier
{
    private const int ForegroundNotificationId = 1;
    private Bitmap? _largeIcon;
    private static volatile string? _shortSpeedText;
    private static volatile string? _detailSpeedText;
    private static long _lastDlBytesPerSec;
    private static volatile bool _useSpeedIcon;

    public int NotificationId => ForegroundNotificationId;

    public static void UpdateLiveSpeed(Context context, long downloadBytesPerSec, long uploadBytesPerSec, bool useSpeedIcon)
    {
        Interlocked.Exchange(ref _lastDlBytesPerSec, downloadBytesPerSec);
        _useSpeedIcon = useSpeedIcon;
        _shortSpeedText = $"↓{FormatCompact(downloadBytesPerSec)}";
        _detailSpeedText = $"↓ {ByteSize.Format(downloadBytesPerSec)}/s · ↑ {ByteSize.Format(uploadBytesPerSec)}/s";

        if (!OperatingSystem.IsAndroidVersionAtLeast(36) && !useSpeedIcon)
        {
            return;
        }

        try
        {
            if (context.GetSystemService(Context.NotificationService) is NotificationManager manager)
            {
                var notifier = new VpnForegroundNotifier();
                manager.Notify(ForegroundNotificationId, notifier.Build(context));
            }
        }
        catch
        {
        }
    }

    public Notification Build(Context context)
    {
        NotificationChannels.Ensure(context);
        var text = NotificationText.Current;
        var body = _detailSpeedText ?? text.Get(context, OverviewLangResourceKind.HintOn);
        var builder = new Notification.Builder(context, NotificationChannels.Service)
            .SetContentTitle(text.Get(context, OverviewLangResourceKind.StatusOn))
            .SetContentText(body)
            .SetContentIntent(NotificationChannels.OpenApp(context))
            .SetOngoing(true)
            .SetShowWhen(false);
        if (_useSpeedIcon)
        {
            builder.SetSmallIcon(StatusBarSpeedIcon.Render(Interlocked.Read(ref _lastDlBytesPerSec)));
        }
        else
        {
            builder.SetSmallIcon(Resource.Drawable.ic_stat_shield);
        }

        if (GetLargeIcon(context) is { } large)
        {
            builder.SetLargeIcon(large);
        }

        // Android 16 (API 36+) 原生實況膠囊（Live Updates / Promoted Ongoing）：無需廠商白名單即可上島顯示
        if (OperatingSystem.IsAndroidVersionAtLeast(36))
        {
            ApplyLiveUpdatePromotion(builder, _shortSpeedText ?? "Protected");
        }

        return builder.Build()!;
    }

    public PendingIntent? CreateConfigureIntent(Context context) => NotificationChannels.OpenApp(context);

    private static void ApplyLiveUpdatePromotion(Notification.Builder builder, string shortText)
    {
        try
        {
            // Android 16: android.app.Notification$Builder.setRequestPromotedOngoing(boolean) / setShortCriticalText(String)
            var builderClass = builder.Class;
            if (Java.Lang.Boolean.True is { } yes)
            {
                var promoteMethod = builderClass.GetMethod("setRequestPromotedOngoing", Java.Lang.Boolean.Type!);
                promoteMethod?.Invoke(builder, [yes]);
            }

            var shortMethod = builderClass.GetMethods().FirstOrDefault(m => m.Name == "setShortCriticalText");
            shortMethod?.Invoke(builder, [new Java.Lang.String(shortText)]);
        }
        catch
        {
            // 低於 Android 16 或 OEM 未實作該公開反射簽章時靜默忽略
        }
    }

    private static string FormatCompact(long bytes) => bytes switch
    {
        < 1024 => $"{bytes}B",
        < 1024 * 1024 => $"{bytes / 1024.0:0.#}K",
        < 1024L * 1024 * 1024 => $"{bytes / (1024.0 * 1024):0.#}M",
        _ => $"{bytes / (1024.0 * 1024 * 1024):0.#}G",
    };

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
