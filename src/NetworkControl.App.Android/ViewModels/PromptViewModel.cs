using System.Net.Sockets;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NetworkControl.Android.Localization;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Prompts;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Android.ViewModels;

/// <summary>
/// 應用內詢問（docs/android.md §6.2）：訂閱核心的詢問事件，以覆蓋層逐個顯示。
/// 只提供「此應用的任意連線」×「永久 / 1 小時 / 直到重新啟動」，防誤觸：出現後 1 秒內按鈕不可用。
/// </summary>
public sealed partial class PromptViewModel : LocalizedViewModel
{
    private static readonly TimeSpan GuardDelay = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan HoldInterval = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan SnoozeDuration = TimeSpan.FromMinutes(10);

    private readonly Dictionary<Guid, PromptSnapshot> _pending = [];
    /// <summary>「稍後」擱置的應用及擱置截止時間：同一應用重試時會產生新的詢問，按應用擱置才不會反覆彈出。</summary>
    private readonly Dictionary<string, DateTimeOffset> _snoozed = new(StringComparer.Ordinal);
    private CancellationTokenSource? _subscription;
    private readonly DispatcherTimer _hold;

    public PromptViewModel(UiContext context)
        : base(context)
    {
        // 詢問顯示在前台時定期推遲超時：詢問因超時被移除時，下一個詢問會頂替到同一位置，用戶可能點到錯誤的應用上
        _hold = new DispatcherTimer(HoldInterval, DispatcherPriority.Background, (_, _) => Hold());
        _hold.Start();
    }

    /// <summary>界面是否在前台（由主視圖模型設置）。</summary>
    public bool IsForeground { get; set; } = true;

    [ObservableProperty]
    public partial PromptSnapshot? Current { get; set; }

    [ObservableProperty]
    public partial bool IsInteractive { get; set; }

    [ObservableProperty]
    public partial bool IsBusy { get; set; }

    /// <summary>0 永久、1 一小時、2 直到重新啟動。</summary>
    [ObservableProperty]
    public partial int DurationIndex { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanAllow))]
    public partial bool DontAskAgain { get; set; }

    public bool CanAllow => !DontAskAgain;

    [ObservableProperty]
    public partial Bitmap? Icon { get; set; }

    public bool IsVisible => Current is not null;

    public bool ShowDontAskAgain => !Backend.Preferences.PromptDenyPermanent;

    public bool ShowDetails => Backend.Preferences.PromptShowDetails && Current is { Flows.Count: > 0 };

    public bool ShowTimedOption => Backend.Preferences.PromptShowTimedOption;

    public string TimedLabel => Loc.Format(PromptLangResourceKind.TimedShort, Backend.Preferences.PromptTimedMinutes);

    public string NetworkBadgeText
    {
        get
        {
            try
            {
                var cm = global::Android.App.Application.Context.GetSystemService(global::Android.Content.Context.ConnectivityService) as global::Android.Net.ConnectivityManager;
                var caps = cm?.GetNetworkCapabilities(cm.ActiveNetwork);
                if (caps is not null)
                {
                    if (caps.HasTransport(global::Android.Net.TransportType.Wifi) || caps.HasTransport(global::Android.Net.TransportType.Ethernet))
                    {
                        return Loc.Get(PromptLangResourceKind.NetWlan);
                    }

                    if (caps.HasTransport(global::Android.Net.TransportType.Cellular))
                    {
                        return Loc.Get(PromptLangResourceKind.NetMobile);
                    }
                }
            }
            catch
            {
            }

            return Loc.Get(PromptLangResourceKind.NetAny);
        }
    }

    public string AppDisplayName => Current is null ? "" : AppName(Current);

    public string RequestMessage => Current is not { } p
        ? ""
        : Loc.Format(PromptLangResourceKind.RequestMessage, "PocketGate", AppName(p), NetworkBadgeText);

    public string DetailText => string.IsNullOrEmpty(MoreText) ? TargetText : $"{TargetText} · {MoreText}";

    public string Title => Current is not { } p ? "" : Loc.Format(p.IsTamperAlert ? PromptLangResourceKind.TamperTitle : PromptLangResourceKind.Title, AppName(p));

    public string PackageName => Current is { } p && PackageAppPath.TryGetPackage(p.App?.Path ?? p.ExecutablePath, out var package) ? package : "";

    public bool IsTamper => Current?.IsTamperAlert == true;

    public string TargetText
    {
        get
        {
            if (Current is not { Flows.Count: > 0 } p)
            {
                return "";
            }

            var flow = p.Flows[^1];
            // 假 DNS 地址沒有意義：有域名時只顯示域名
            var address = flow.Domains.Count > 0 ? flow.Domains[0]
                : flow.Remote.AddressFamily == AddressFamily.InterNetworkV6 ? $"[{flow.Remote.Address}]" : flow.Remote.Address.ToString();
            var protocol = flow.Protocol == L4Protocol.Udp ? "UDP" : "TCP";
            return $"{address}:{flow.Remote.Port} {protocol}";
        }
    }

    public string MoreText => Current is { Flows.Count: > 1 } p ? Loc.Format(PromptLangResourceKind.MoreConnections, p.Flows.Count) : "";

    public int QueueCount => _pending.Values.Count(p => p.Id != Current?.Id && !IsSnoozed(p));

    public string QueueText => QueueCount > 0 ? Loc.Format(PromptLangResourceKind.Queue, QueueCount) : "";

    /// <summary>開始訂閱詢問事件（UI 線程）。</summary>
    public void Start()
    {
        _subscription = new CancellationTokenSource();
        var token = _subscription.Token;
        _ = Task.Run(async () =>
        {
            try
            {
                await foreach (var evt in Backend.SubscribePromptsAsync(token))
                {
                    Dispatcher.UIThread.Post(() => OnEvent(evt));
                }
            }
            catch (OperationCanceledException)
            {
                // 停止
            }
        }, token);
    }

    /// <summary>打開指定的詢問（例如從通知點擊進入）；被「稍後」擱置的詢問也會重新顯示。</summary>
    public void Open(Guid promptId)
    {
        if (_pending.TryGetValue(promptId, out var prompt))
        {
            _snoozed.Remove(AppKey(prompt));
            Show(prompt);
        }
    }

    /// <summary>重新顯示第一個待決詢問（總覽頁的「查看」）。</summary>
    public void OpenFirst()
    {
        _snoozed.Clear();
        ShowNext();
    }

    [RelayCommand]
    private Task AllowAsync() => AnswerAsync(VerdictAction.Allow, RuleLifetime.Permanent, null);

    [RelayCommand]
    private Task AllowOnceAsync() => AnswerAsync(VerdictAction.Allow, RuleLifetime.UntilRestart, null);

    [RelayCommand]
    private Task AllowTimedAsync() => AnswerAsync(
        VerdictAction.Allow,
        RuleLifetime.Timed,
        TimeSpan.FromMinutes(Backend.Preferences.PromptTimedMinutes));

    [RelayCommand]
    private Task BlockAsync() => AnswerAsync(
        VerdictAction.Deny,
        Backend.Preferences.PromptDenyPermanent || DontAskAgain ? RuleLifetime.Permanent : RuleLifetime.UntilRestart,
        null);

    [RelayCommand]
    private void Later()
    {
        if (Current is { } p)
        {
            _snoozed[AppKey(p)] = DateTimeOffset.UtcNow + SnoozeDuration;
        }

        ShowNext();
    }

    private async Task AnswerAsync(VerdictAction action, RuleLifetime lifetime, TimeSpan? duration)
    {
        if (Current is not { } prompt || !IsInteractive)
        {
            return;
        }

        IsBusy = true;
        try
        {
            await Backend.AnswerPromptAsync(prompt.Id, action, lifetime, duration, CancellationToken.None);
            Platform.PromptNotifier.Cancel(global::Android.App.Application.Context, prompt.Id);
        }
        finally
        {
            IsBusy = false;
        }

        _pending.Remove(prompt.Id);
        ShowNext();
    }

    private void OnEvent(PromptEvent evt)
    {
        switch (evt.Kind)
        {
            case PromptEventKind.Removed:
                _pending.Remove(evt.Prompt.Id);
                if (Current?.Id == evt.Prompt.Id)
                {
                    ShowNext();
                }

                break;
            default:
                _pending[evt.Prompt.Id] = evt.Prompt;
                if (Current?.Id == evt.Prompt.Id)
                {
                    // 合併了新的連線：只刷新內容，不重置防誤觸
                    Current = evt.Prompt;
                }
                else if (Current is null && !IsSnoozed(evt.Prompt))
                {
                    Show(evt.Prompt);
                }

                break;
        }

        OnPropertyChanged(nameof(QueueCount));
        OnPropertyChanged(nameof(QueueText));
    }

    private void ShowNext()
    {
        var next = _pending.Values.Where(p => !IsSnoozed(p)).OrderBy(p => p.CreatedAt).FirstOrDefault();
        if (next is null)
        {
            Current = null;
        }
        else
        {
            Show(next);
        }
    }

    private void Show(PromptSnapshot prompt)
    {
        Current = prompt;
        Hold();
        DurationIndex = 0;
        DontAskAgain = false;
        IsInteractive = false;
        Icon = null;
        _ = EnableAfterGuardAsync(prompt.Id);
        if (PackageName is { Length: > 0 } package)
        {
            _ = LoadIconAsync(prompt.Id, package);
        }
    }

    partial void OnCurrentChanged(PromptSnapshot? value) => OnPropertyChanged(string.Empty);

    private async Task EnableAfterGuardAsync(Guid promptId)
    {
        await Task.Delay(GuardDelay);
        if (Current?.Id == promptId)
        {
            IsInteractive = true;
        }
    }

    private async Task LoadIconAsync(Guid promptId, string package)
    {
        var icon = await Context.Icons.LoadAsync(package);
        if (Current?.Id == promptId)
        {
            Icon = icon;
        }
    }

    private static string AppKey(PromptSnapshot prompt) => prompt.App?.Path ?? prompt.ExecutablePath ?? prompt.Id.ToString();

    private bool IsSnoozed(PromptSnapshot prompt) =>
        _snoozed.TryGetValue(AppKey(prompt), out var until) && until > DateTimeOffset.UtcNow;

    private void Hold()
    {
        if (IsForeground && Current is { } prompt)
        {
            Backend.HoldPrompt(prompt.Id);
        }
    }

    private static string AppName(PromptSnapshot prompt) => prompt.App?.Name ?? prompt.ExecutablePath ?? "?";
}
