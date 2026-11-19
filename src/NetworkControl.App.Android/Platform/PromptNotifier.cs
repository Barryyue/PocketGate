using global::Android.App;
using global::Android.Content;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Prompts;

namespace NetworkControl.Android.Platform;

/// <summary>
/// 把詢問推送為系統通知（docs/android.md §6.1）。核心啟動時即訂閱，因此界面沒有打開時判決管線也認為有用戶會話。
/// 通知上的「允許 / 拒絕」按鈕生成此應用任意連線的永久規則；點擊通知正文打開應用內的完整詢問。
/// 文本來自 AtomUI Catalog（與界面同源），語言切換後由 <see cref="RefreshPending"/> 刷新已顯示的通知。
/// </summary>
public sealed partial class PromptNotifier(
    Context context,
    PromptBroker prompts,
    UiPreferences preferences,
    PromptOverlayService overlay,
    ILogger<PromptNotifier> logger) : BackgroundService
{
    private readonly Context _context = context.ApplicationContext ?? context;
    private bool _isUiForeground;

    /// <summary>詢問新增、更新或移除後觸發（後台線程）。</summary>
    public event EventHandler? PromptsChanged;

    /// <summary>界面在前台並且自己顯示詢問時設為 true：此時只更新通知，不懸浮打擾。</summary>
    public bool IsUiForeground
    {
        get => _isUiForeground;
        set
        {
            _isUiForeground = value;
            if (value)
            {
                overlay.Hide();
            }
            else if (preferences.PromptOverlayEnabled && overlay.CanDrawOverlays())
            {
                var first = prompts.GetPending().FirstOrDefault();
                if (first is not null)
                {
                    overlay.Show(first);
                }
            }
        }
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        NotificationChannels.Ensure(_context);
        try
        {
            await foreach (var evt in prompts.SubscribeAsync(new PromptSubscriber(UserId: null, IsAdmin: true), stoppingToken))
            {
                try
                {
                    if (evt.Kind == PromptEventKind.Removed)
                    {
                        overlay.HideIfCurrent(evt.Prompt.Id);
                        Cancel(evt.Prompt.Id);
                    }
                    else
                    {
                        if (!IsUiForeground && preferences.PromptOverlayEnabled && overlay.CanDrawOverlays())
                        {
                            overlay.Show(evt.Prompt);
                        }

                        Show(evt.Prompt);
                    }
                }
                catch (Java.Lang.Exception ex)
                {
                    LogNotifyFailed(logger, ex);
                }

                PromptsChanged?.Invoke(this, EventArgs.Empty);
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // 停止
        }
    }

    /// <summary>取消某個詢問的通知（例如在通知上點了按鈕）。</summary>
    public static void Cancel(Context context, Guid promptId)
    {
        if (context.GetSystemService(Context.NotificationService) is NotificationManager manager)
        {
            manager.Cancel(promptId.ToString(), NotificationIdFor(promptId));
        }
    }

    internal static int NotificationIdFor(Guid promptId) => 1000 + (promptId.GetHashCode() & 0x0FFFFFFF);

    /// <summary>語言切換後用新文本重新顯示所有待決詢問的通知。</summary>
    public void RefreshPending()
    {
        foreach (var prompt in prompts.GetPending())
        {
            try
            {
                Show(prompt);
            }
            catch (Java.Lang.Exception ex)
            {
                LogNotifyFailed(logger, ex);
            }
        }
    }

    private void Cancel(Guid promptId) => Cancel(_context, promptId);

    private void Show(PromptSnapshot prompt)
    {
        if (_context.GetSystemService(Context.NotificationService) is not NotificationManager manager)
        {
            return;
        }

        var localizer = NotificationText.Current;
        var appName = prompt.App?.Name ?? prompt.ExecutablePath ?? localizer.Get(_context, CommonLangResourceKind.UnknownApp);
        var title = prompt.IsTamperAlert
            ? localizer.Format(_context, PromptLangResourceKind.TamperTitle, appName)
            : localizer.Format(_context, PromptLangResourceKind.Title, appName);
        var flow = prompt.Flows.Count > 0 ? prompt.Flows[^1] : null;
        var host = flow is null ? "" : flow.Domains.Count > 0 ? flow.Domains[0] : FormatAddress(flow.Remote.Address);
        var target = flow is null ? "" : $"{host}:{flow.Remote.Port} {Protocol(flow.Protocol)}";
        var text = prompt.Flows.Count > 1
            ? localizer.Format(_context, PromptLangResourceKind.NotificationMultiple, target, prompt.Flows.Count)
            : target;

        // 界面在前台時界面自己顯示詢問：通知放到靜默渠道，不懸浮打擾；更新已有通知時不重複提醒（SetOnlyAlertOnce）
        var channel = IsUiForeground ? NotificationChannels.Service : NotificationChannels.Prompts;
        var builder = new Notification.Builder(_context, channel)
            .SetSmallIcon(Resource.Drawable.ic_stat_shield)
            .SetContentTitle(title)
            .SetContentText(text)
            .SetStyle(new Notification.BigTextStyle().BigText(text + "\n" + localizer.Get(_context, PromptLangResourceKind.Hint)))
            .SetContentIntent(NotificationChannels.OpenApp(_context, prompt.Id))
            .SetCategory(Notification.CategoryRecommendation)
            .SetAutoCancel(true)
            .SetOnlyAlertOnce(true)
            .SetWhen(prompt.CreatedAt.ToUnixTimeMilliseconds())
            .AddAction(new Notification.Action.Builder(null, localizer.Get(_context, CommonLangResourceKind.Block), ActionIntent(prompt.Id, VerdictAction.Deny)).Build())
            .AddAction(new Notification.Action.Builder(null, localizer.Get(_context, CommonLangResourceKind.Allow), ActionIntent(prompt.Id, VerdictAction.Allow)).Build());

        manager.Notify(prompt.Id.ToString(), NotificationIdFor(prompt.Id), builder.Build());
    }

    private PendingIntent ActionIntent(Guid promptId, VerdictAction action)
    {
        var intent = new Intent(_context, typeof(PromptActionReceiver))
            .SetAction(PromptActionReceiver.ActionAnswer)
            .PutExtra(PromptActionReceiver.ExtraPromptId, promptId.ToString())
            .PutExtra(PromptActionReceiver.ExtraAllow, action == VerdictAction.Allow);
        var requestCode = NotificationIdFor(promptId) * 2 + (action == VerdictAction.Allow ? 1 : 0);
        return PendingIntent.GetBroadcast(_context, requestCode, intent, PendingIntentFlags.Immutable | PendingIntentFlags.UpdateCurrent)!;
    }

    private static string FormatAddress(System.Net.IPAddress address) =>
        address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6 ? $"[{address}]" : address.ToString();

    private static string Protocol(L4Protocol protocol) => protocol switch
    {
        L4Protocol.Tcp => "TCP",
        L4Protocol.Udp => "UDP",
        _ => protocol.ToString().ToUpperInvariant(),
    };

    [LoggerMessage(Level = LogLevel.Warning, Message = "顯示詢問通知失敗")]
    private static partial void LogNotifyFailed(ILogger logger, Exception exception);
}
