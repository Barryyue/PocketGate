using global::Android.App;
using global::Android.Content;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Core.Prompts;

namespace NetworkControl.Android.Platform;

/// <summary>
/// 勿擾結束後發一條低優先級摘要通知（M3-07）；點擊打開阻擋記錄頁並清除摘要。
/// 界面沒有打開時也有效（開機恢復、系統拉起 VPN 服務）。
/// </summary>
public sealed partial class QuietPeriodNotifier(
    Context context,
    QuietPeriodTracker tracker,
    ILogger<QuietPeriodNotifier> logger) : IHostedService
{
    private const int NotificationId = 4000;

    private readonly Context _context = context.ApplicationContext ?? context;

    public Task StartAsync(CancellationToken cancellationToken)
    {
        tracker.SummaryReady += OnSummaryReady;
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        tracker.SummaryReady -= OnSummaryReady;
        return Task.CompletedTask;
    }

    private void OnSummaryReady(object? sender, QuietPeriodSummary summary)
    {
        try
        {
            if (_context.GetSystemService(Context.NotificationService) is not NotificationManager manager)
            {
                return;
            }

            var text = NotificationText.Current;
            var builder = new Notification.Builder(_context, NotificationChannels.QuietSummary)
                .SetSmallIcon(Resource.Drawable.ic_stat_shield)
                .SetContentTitle(text.Get(_context, SettingsLangResourceKind.QuietSummaryTitle))
                .SetContentText(text.Format(_context, SettingsLangResourceKind.QuietSummaryText, summary.Apps.Count, summary.Denied))
                .SetStyle(new Notification.BigTextStyle().BigText(
                    text.Format(_context, SettingsLangResourceKind.QuietSummaryText, summary.Apps.Count, summary.Denied)))
                .SetContentIntent(NotificationChannels.OpenApp(_context, tab: 2, dismissQuietSummary: true))
                .SetCategory(Notification.CategoryRecommendation)
                .SetAutoCancel(true)
                .SetWhen(summary.To.ToUnixTimeMilliseconds());
            manager.Notify(NotificationId, builder.Build());
        }
        catch (Java.Lang.Exception ex)
        {
            LogNotifyFailed(logger, ex);
        }
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "發送勿擾摘要通知失敗")]
    private static partial void LogNotifyFailed(ILogger logger, Exception exception);
}
