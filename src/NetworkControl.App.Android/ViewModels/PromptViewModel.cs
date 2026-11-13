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

    private readonly Dictionary<Guid, PromptSnapshot> _pending = [];
    private readonly HashSet<Guid> _deferred = [];
    private CancellationTokenSource? _subscription;

    public PromptViewModel(UiContext context)
        : base(context)
    {
    }

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
    public partial Bitmap? Icon { get; set; }

    public bool IsVisible => Current is not null;

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
            var address = flow.Remote.AddressFamily == AddressFamily.InterNetworkV6 ? $"[{flow.Remote.Address}]" : flow.Remote.Address.ToString();
            var protocol = flow.Protocol == L4Protocol.Udp ? "UDP" : "TCP";
            return $"{address}:{flow.Remote.Port} {protocol}";
        }
    }

    public string MoreText => Current is { Flows.Count: > 1 } p ? Loc.Format(PromptLangResourceKind.MoreConnections, p.Flows.Count) : "";

    public int QueueCount => _pending.Keys.Count(id => id != Current?.Id && !_deferred.Contains(id));

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
        _deferred.Remove(promptId);
        if (_pending.TryGetValue(promptId, out var prompt))
        {
            Show(prompt);
        }
    }

    /// <summary>重新顯示第一個待決詢問（總覽頁的「查看」）。</summary>
    public void OpenFirst()
    {
        _deferred.Clear();
        ShowNext();
    }

    [RelayCommand]
    private Task AllowAsync() => AnswerAsync(VerdictAction.Allow);

    [RelayCommand]
    private Task BlockAsync() => AnswerAsync(VerdictAction.Deny);

    [RelayCommand]
    private void Later()
    {
        if (Current is { } p)
        {
            _deferred.Add(p.Id);
        }

        ShowNext();
    }

    private async Task AnswerAsync(VerdictAction action)
    {
        if (Current is not { } prompt || !IsInteractive)
        {
            return;
        }

        var (lifetime, duration) = DurationIndex switch
        {
            1 => (RuleLifetime.Timed, (TimeSpan?)TimeSpan.FromHours(1)),
            2 => (RuleLifetime.UntilRestart, null),
            _ => (RuleLifetime.Permanent, null),
        };

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
                _deferred.Remove(evt.Prompt.Id);
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
                else if (Current is null && !_deferred.Contains(evt.Prompt.Id))
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
        var next = _pending.Values.Where(p => !_deferred.Contains(p.Id)).OrderBy(p => p.CreatedAt).FirstOrDefault();
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
        DurationIndex = 0;
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

    private static string AppName(PromptSnapshot prompt) => prompt.App?.Name ?? prompt.ExecutablePath ?? "?";
}
