using global::Android.App;
using global::Android.Content;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;

namespace NetworkControl.Android.Platform;

/// <summary>
/// 通知渠道（Android 8+）。名稱和說明取自 AtomUI Catalog（與界面同源，docs/m3-tasks.md M3-10），
/// 語言切換後由 <c>Backend.RefreshNotifications</c> 重新調用 <see cref="Ensure"/> 更新。
/// </summary>
internal static class NotificationChannels
{
    /// <summary>聯網詢問：懸浮顯示。</summary>
    public const string Prompts = "prompts";

    /// <summary>VPN 前台服務的常駐通知：靜默。</summary>
    public const string Service = "service";

    /// <summary>保護被系統停止等提醒。</summary>
    public const string Alerts = "alerts";

    /// <summary>按網路自動切換配置文件：低優先級，不發出聲音。</summary>
    public const string AutoSwitch = "autoswitch";

    /// <summary>勿擾結束後的摘要：低優先級，不發出聲音。</summary>
    public const string QuietSummary = "quietsummary";

    public static void Ensure(Context context)
    {
        if (context.GetSystemService(Context.NotificationService) is not NotificationManager manager)
        {
            return;
        }

        var text = NotificationText.Current;
        manager.CreateNotificationChannel(new NotificationChannel(Prompts, text.Get(context, PromptLangResourceKind.ChannelPrompts), NotificationImportance.High)
        {
            Description = text.Get(context, PromptLangResourceKind.ChannelPromptsDescription),
        });
        manager.CreateNotificationChannel(new NotificationChannel(Service, text.Get(context, PromptLangResourceKind.ChannelService), NotificationImportance.Low)
        {
            Description = text.Get(context, PromptLangResourceKind.ChannelServiceDescription),
        });
        manager.CreateNotificationChannel(new NotificationChannel(Alerts, text.Get(context, PromptLangResourceKind.ChannelAlerts), NotificationImportance.Default));
        manager.CreateNotificationChannel(new NotificationChannel(AutoSwitch, text.Get(context, SettingsLangResourceKind.AutoSwitchChannel), NotificationImportance.Low)
        {
            Description = text.Get(context, SettingsLangResourceKind.AutoSwitchChannelDescription),
        });
        manager.CreateNotificationChannel(new NotificationChannel(QuietSummary, text.Get(context, SettingsLangResourceKind.QuietSummaryChannel), NotificationImportance.Low)
        {
            Description = text.Get(context, SettingsLangResourceKind.QuietSummaryChannelDescription),
        });
    }

    /// <summary>打開主界面的 PendingIntent；<paramref name="promptId"/> 有值時界面打開對應的詢問。</summary>
    public static PendingIntent OpenApp(Context context, Guid? promptId = null, int? tab = null, bool dismissQuietSummary = false)
    {
        var intent = new Intent(context, typeof(MainActivity))
            .SetFlags(ActivityFlags.NewTask | ActivityFlags.SingleTop);
        if (promptId is { } id)
        {
            intent.PutExtra(MainActivity.ExtraPromptId, id.ToString());
        }

        if (tab is { } index)
        {
            intent.PutExtra(MainActivity.ExtraTab, index);
        }

        if (dismissQuietSummary)
        {
            intent.PutExtra(MainActivity.ExtraDismissQuietSummary, true);
        }

        var requestCode = promptId?.GetHashCode() ?? (tab ?? 0) + (dismissQuietSummary ? 100 : 0);
        return PendingIntent.GetActivity(context, requestCode, intent, PendingIntentFlags.Immutable | PendingIntentFlags.UpdateCurrent)!;
    }
}
