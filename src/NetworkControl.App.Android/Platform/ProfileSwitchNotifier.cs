using global::Android.App;
using global::Android.Content;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Core.Profiles;

namespace NetworkControl.Android.Platform;

/// <summary>
/// 自動切換配置文件時發一條低優先級通知（M3-04）。訂閱核心的 <see cref="ProfileAutoSwitcher.Switched"/>，
/// 因此界面沒有打開時（開機恢復、系統拉起 VPN 服務）也能收到。
/// </summary>
public sealed partial class ProfileSwitchNotifier(
    Context context,
    ProfileAutoSwitcher switcher,
    ILogger<ProfileSwitchNotifier> logger) : IHostedService
{
    private const int NotificationId = 3000;

    private readonly Context _context = context.ApplicationContext ?? context;

    public Task StartAsync(CancellationToken cancellationToken)
    {
        switcher.Switched += OnSwitched;
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        switcher.Switched -= OnSwitched;
        return Task.CompletedTask;
    }

    private void OnSwitched(object? sender, ProfileAutoSwitchEvent e)
    {
        try
        {
            if (_context.GetSystemService(Context.NotificationService) is not NotificationManager manager)
            {
                return;
            }

            var text = NotificationText.Current;
            var network = e.Network.DisplayName ?? e.Network.Kind.ToString();
            var builder = new Notification.Builder(_context, NotificationChannels.AutoSwitch)
                .SetSmallIcon(Resource.Drawable.ic_stat_shield)
                .SetContentTitle(text.Format(_context, SettingsLangResourceKind.AutoSwitchTitle, e.Profile.Name))
                .SetContentText(text.Format(_context, SettingsLangResourceKind.AutoSwitchText, network))
                .SetContentIntent(NotificationChannels.OpenApp(_context))
                .SetCategory(Notification.CategoryRecommendation)
                .SetAutoCancel(true);
            manager.Notify(NotificationId, builder.Build());
        }
        catch (Java.Lang.Exception ex)
        {
            LogNotifyFailed(logger, ex);
        }
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "發送自動切換通知失敗")]
    private static partial void LogNotifyFailed(ILogger logger, Exception exception);
}
