using global::Android.App;
using global::Android.Content;

namespace NetworkControl.Android.Platform;

/// <summary>通知渠道（Android 8+）。名稱來自 Android 字符串資源，跟隨應用語言。</summary>
internal static class NotificationChannels
{
    /// <summary>聯網詢問：懸浮顯示。</summary>
    public const string Prompts = "prompts";

    /// <summary>VPN 前台服務的常駐通知：靜默。</summary>
    public const string Service = "service";

    /// <summary>保護被系統停止等提醒。</summary>
    public const string Alerts = "alerts";

    public static void Ensure(Context context)
    {
        if (context.GetSystemService(Context.NotificationService) is not NotificationManager manager)
        {
            return;
        }

        manager.CreateNotificationChannel(new NotificationChannel(Prompts, context.GetString(Resource.String.channel_prompts), NotificationImportance.High)
        {
            Description = context.GetString(Resource.String.channel_prompts_description),
        });
        manager.CreateNotificationChannel(new NotificationChannel(Service, context.GetString(Resource.String.channel_service), NotificationImportance.Low)
        {
            Description = context.GetString(Resource.String.channel_service_description),
        });
        manager.CreateNotificationChannel(new NotificationChannel(Alerts, context.GetString(Resource.String.channel_alerts), NotificationImportance.Default));
    }

    /// <summary>打開主界面的 PendingIntent；<paramref name="promptId"/> 有值時界面打開對應的詢問。</summary>
    public static PendingIntent OpenApp(Context context, Guid? promptId = null)
    {
        var intent = new Intent(context, typeof(MainActivity))
            .SetFlags(ActivityFlags.NewTask | ActivityFlags.SingleTop);
        if (promptId is { } id)
        {
            intent.PutExtra(MainActivity.ExtraPromptId, id.ToString());
        }

        var requestCode = promptId?.GetHashCode() ?? 0;
        return PendingIntent.GetActivity(context, requestCode, intent, PendingIntentFlags.Immutable | PendingIntentFlags.UpdateCurrent)!;
    }
}
