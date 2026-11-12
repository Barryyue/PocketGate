using System.Collections.Concurrent;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.Input;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using NetworkControl.App.Localization;
using NetworkControl.App.ViewModels;
using NetworkControl.App.Views;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Services;

/// <summary>應用級操作的入口（由 App 提供實現）：打開主窗口、退出界面。供托盤、通知、主窗口調用。</summary>
public sealed class ShellActivator
{
    public Action? Activate { get; set; }

    public Action? QuitApplication { get; set; }

    /// <summary>完全退出：停止核心服務（攔截停止）並退出界面。</summary>
    public Action? QuitAll { get; set; }

    public void Show() => Activate?.Invoke();

    public void Quit() => QuitApplication?.Invoke();

    public void QuitEverything() => QuitAll?.Invoke();
}

/// <summary>右下角的輕量通知卡片。</summary>
public sealed class ToastViewModel(string title, string body, string? actionText, Func<Task>? action) : ViewModelBase
{
    public string Title { get; } = title;

    public string Body { get; } = body;

    public string? ActionText { get; } = actionText;

    public bool HasAction => action is not null;

    public event EventHandler? CloseRequested;

    public IAsyncRelayCommand ActionCommand => new AsyncRelayCommand(async () =>
    {
        CloseRequested?.Invoke(this, EventArgs.Empty);
        if (action is not null)
        {
            await action();
        }
    });

    public IRelayCommand CloseCommand => new RelayCommand(() => CloseRequested?.Invoke(this, EventArgs.Empty));
}

/// <summary>
/// 通知窗口管理：與主窗口無關（主窗口可能已經關到托盤），在屏幕右下角堆疊顯示，自動消失，最多同時 3 條。
/// </summary>
public sealed class ToastService
{
    private const int MaxVisible = 3;
    private static readonly TimeSpan Lifetime = TimeSpan.FromSeconds(8);

    private readonly List<ToastWindow> _windows = [];

    public void Show(ToastViewModel toast)
    {
        Dispatcher.UIThread.VerifyAccess();
        if (_windows.Count >= MaxVisible)
        {
            _windows[0].Close();
        }

        var window = new ToastWindow { DataContext = toast };
        toast.CloseRequested += (_, _) => window.Close();
        window.Closed += (_, _) =>
        {
            _windows.Remove(window);
            Relayout();
        };

        _windows.Add(window);
        window.Show();
        Relayout();
        DispatcherTimer.RunOnce(() => window.Close(), Lifetime);
    }

    private void Relayout()
    {
        var offset = 0.0;
        for (var i = _windows.Count - 1; i >= 0; i--)
        {
            offset += _windows[i].PlaceAboveBottom(offset);
        }
    }
}

/// <summary>
/// 白名單模式下，未知應用被拒絕時給出通知（FR-NTF-01），可以一鍵創建允許規則。
/// 同一應用 5 分鐘內只通知一次，避免刷屏。
/// </summary>
public sealed class BlockedNotifier
{
    private static readonly TimeSpan Cooldown = TimeSpan.FromMinutes(5);

    private readonly ServiceConnection _connection;
    private readonly ToastService _toasts;
    private readonly RuleEditing _editing;
    private readonly ShellActivator _shell;
    private readonly DisplayText _text;
    private readonly ConcurrentDictionary<string, DateTimeOffset> _lastNotified = new(StringComparer.OrdinalIgnoreCase);
    private CancellationTokenSource? _watch;

    public BlockedNotifier(ServiceConnection connection, ToastService toasts, RuleEditing editing, ShellActivator shell, DisplayText text)
    {
        _connection = connection;
        _toasts = toasts;
        _editing = editing;
        _shell = shell;
        _text = text;
        connection.Connected += (_, clients) => _ = WatchAsync(clients);
    }

    private async Task WatchAsync(IpcClients clients)
    {
        if (_watch is not null)
        {
            await _watch.CancelAsync();
            _watch.Dispose();
        }

        _watch = new CancellationTokenSource();
        try
        {
            using var call = clients.Activity.WatchConnections(new Empty(), cancellationToken: _watch.Token);
            await foreach (var evt in call.ResponseStream.ReadAllAsync(_watch.Token))
            {
                if (ShouldNotify(evt))
                {
                    Notify(evt);
                }
            }
        }
        catch (Exception ex) when (ex is RpcException or OperationCanceledException or IOException)
        {
            // 連接斷開；重連後會重新訂閱
        }
    }

    private bool ShouldNotify(Proto.ConnectionEvent evt)
    {
        if (evt.Verdict.Action != Proto.VerdictAction.Deny
            || evt.Verdict.Reason != Proto.VerdictReason.Mode
            || _connection.Status?.Mode != Proto.FilterMode.Whitelist
            || !evt.HasAppPath)
        {
            return false;
        }

        var now = DateTimeOffset.UtcNow;
        if (_lastNotified.TryGetValue(evt.AppPath, out var last) && now - last < Cooldown)
        {
            return false;
        }

        _lastNotified[evt.AppPath] = now;
        return true;
    }

    private void Notify(Proto.ConnectionEvent evt)
    {
        var loc = _text.Localization;
        var app = evt.HasAppName ? evt.AppName : DisplayText.FileName(evt.AppPath);
        var target = evt.HasHostname ? evt.Hostname : DisplayText.Endpoint(evt.Remote);
        var appPath = evt.AppPath;

        _toasts.Show(new ToastViewModel(
            loc.Get(NotificationsLangResourceKind.BlockedTitle),
            loc.Format(NotificationsLangResourceKind.BlockedBody, app, target),
            loc.Get(NotificationsLangResourceKind.AllowApp),
            async () =>
            {
                // 規則編輯對話框需要宿主窗口：先打開主窗口
                _shell.Show();
                await Task.Delay(200);
                await _editing.CreateAsync(new RulePrefill(AppPath: appPath, Action: Proto.VerdictAction.Allow));
            }));
    }
}
