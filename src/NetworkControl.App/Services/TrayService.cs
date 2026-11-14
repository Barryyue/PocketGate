using System.ComponentModel;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Threading;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using NetworkControl.App.Localization;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Services;

/// <summary>
/// 托盤圖標與右鍵菜單（FR-TRY-01 / 02）：
/// 打開主界面 / 規則 / 連線記錄 / 設定、切換過濾模式、一鍵斷網、勿擾、結束介面。
/// 狀態或語言變化時整體重建菜單：Windows 原生托盤菜單在創建後不一定響應單個菜單項的修改。
/// </summary>
public sealed class TrayService : IDisposable
{
    private static readonly Proto.FilterMode[] MenuModes =
    [
        Proto.FilterMode.Ask,
        Proto.FilterMode.Whitelist,
        Proto.FilterMode.Blacklist,
        Proto.FilterMode.Disabled,
    ];

    private readonly ServiceConnection _connection;
    private readonly ProfileCatalog _catalog;
    private readonly DisplayText _text;
    private readonly UpdateChecker _updates;
    private readonly TrayIcon _trayIcon;
    private bool _refreshQueued;

    public TrayService(ServiceConnection connection, ProfileCatalog catalog, DisplayText text, UpdateChecker updates)
    {
        _connection = connection;
        _catalog = catalog;
        _text = text;
        _updates = updates;
        _trayIcon = new TrayIcon { IsVisible = true };
        _trayIcon.Clicked += (_, _) => NavigateRequested?.Invoke(this, null);

        connection.PropertyChanged += OnConnectionChanged;
        catalog.Changed += OnProfilesChanged;
        text.Localization.LanguageChanged += OnLanguageChanged;
        updates.PropertyChanged += OnUpdateChanged;
        Refresh();
    }

    /// <summary>請求打開主窗口；參數為要跳轉的頁面鍵，為 null 時只打開窗口。</summary>
    public event EventHandler<string?>? NavigateRequested;

    /// <summary>只退出界面，核心服務繼續攔截。</summary>
    public event EventHandler? ExitRequested;

    /// <summary>完全退出：停止核心服務並退出界面。</summary>
    public event EventHandler? ExitAllRequested;

    /// <summary>托盤圖標需要掛在 Application 上才能顯示。</summary>
    public void Attach(Application application) => TrayIcon.SetIcons(application, [_trayIcon]);

    public void Dispose()
    {
        _connection.PropertyChanged -= OnConnectionChanged;
        _catalog.Changed -= OnProfilesChanged;
        _text.Localization.LanguageChanged -= OnLanguageChanged;
        _updates.PropertyChanged -= OnUpdateChanged;
        _trayIcon.IsVisible = false;
        _trayIcon.Dispose();
    }

    private void OnConnectionChanged(object? sender, PropertyChangedEventArgs e) => QueueRefresh();

    private void OnProfilesChanged(object? sender, EventArgs e) => QueueRefresh();

    private void OnLanguageChanged(object? sender, EventArgs e) => QueueRefresh();

    private void OnUpdateChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(UpdateChecker.HasUpdate) or nameof(UpdateChecker.AvailableVersion))
        {
            QueueRefresh();
        }
    }

    /// <summary>狀態推送可能很頻繁，合併到下一次 UI 循環統一重建。</summary>
    private void QueueRefresh()
    {
        if (_refreshQueued)
        {
            return;
        }

        _refreshQueued = true;
        Dispatcher.UIThread.Post(() =>
        {
            _refreshQueued = false;
            Refresh();
        }, DispatcherPriority.Background);
    }

    private void Refresh()
    {
        var loc = _text.Localization;
        var status = _connection.Status;

        _trayIcon.Icon = TrayIconRenderer.Render(TrayIconRenderer.FromStatus(status));
        _trayIcon.ToolTipText = status is null
            ? loc.Get(TrayLangResourceKind.TooltipDisconnected)
            : loc.Format(TrayLangResourceKind.TooltipMode, _text.Mode(status.Mode));
        _trayIcon.Menu = BuildMenu(status);
    }

    private async Task ActivateProfileAsync(string id)
    {
        if (_connection.Clients is not { } clients)
        {
            return;
        }

        try
        {
            await clients.Profiles.ActivateAsync(new Proto.ActivateProfileRequest { Id = id });
            await _catalog.RefreshAsync();
        }
        catch (RpcException)
        {
            // 狀態推送會讓菜單回到服務端的真實狀態
        }
    }

    private NativeMenu BuildMenu(Proto.ServiceStatus? status)
    {
        var loc = _text.Localization;
        var connected = status is not null;
        var menu = new NativeMenu();

        menu.Items.Add(Item(loc.Get(TrayLangResourceKind.Open), () => NavigateRequested?.Invoke(this, "overview")));
        menu.Items.Add(Item(loc.Get(TrayLangResourceKind.ShowApps), () => NavigateRequested?.Invoke(this, "apps")));
        menu.Items.Add(Item(loc.Get(TrayLangResourceKind.ShowRules), () => NavigateRequested?.Invoke(this, "rules")));
        menu.Items.Add(Item(loc.Get(TrayLangResourceKind.ShowLog), () => NavigateRequested?.Invoke(this, "log")));
        menu.Items.Add(Item(loc.Get(TrayLangResourceKind.ShowSettings), () => NavigateRequested?.Invoke(this, "settings")));

        // 有新版時放在導航項之後、分隔線之前；點擊打開下載頁（不做自動下載安裝）
        if (_updates.HasUpdate)
        {
            menu.Items.Add(Item(loc.Format(TrayLangResourceKind.UpdateAvailable, _updates.AvailableVersion), _updates.OpenDownloadPage));
        }

        menu.Items.Add(new NativeMenuItemSeparator());

        // 過濾模式
        var modeMenu = new NativeMenu();
        foreach (var mode in MenuModes)
        {
            var item = Item(_text.Mode(mode), () => _ = CallAsync(c => c.Control.SetModeAsync(new Proto.SetModeRequest { Mode = mode }).ResponseAsync), connected);
            item.ToggleType = MenuItemToggleType.Radio;
            item.IsChecked = status?.Mode == mode;
            modeMenu.Items.Add(item);
        }

        menu.Items.Add(new NativeMenuItem(loc.Get(TrayLangResourceKind.Mode)) { Menu = modeMenu, IsEnabled = connected });

        // 配置文件：勾選以狀態推送的 ActiveProfileId 為準，不再用列表快照的 IsActive（R5）
        if (_catalog.HasMultiple)
        {
            var profileMenu = new NativeMenu();
            foreach (var profile in _catalog.Profiles)
            {
                var item = Item(profile.Name, () => _ = ActivateProfileAsync(profile.Id), connected);
                item.ToggleType = MenuItemToggleType.Radio;
                item.IsChecked = profile.Id == status?.ActiveProfileId;
                profileMenu.Items.Add(item);
            }

            menu.Items.Add(new NativeMenuItem(loc.Get(ProfilesLangResourceKind.CardTitle)) { Menu = profileMenu, IsEnabled = connected });
        }

        // 一鍵斷網 / 恢復網路
        if (status?.Mode == Proto.FilterMode.BlockAll)
        {
            menu.Items.Add(Item(loc.Get(TrayLangResourceKind.Restore),
                () => _ = CallAsync(c => c.Control.RestoreFromBlockAllAsync(new Empty()).ResponseAsync), connected));
        }
        else
        {
            menu.Items.Add(Item(loc.Get(TrayLangResourceKind.BlockAll),
                () => _ = CallAsync(c => c.Control.SetModeAsync(new Proto.SetModeRequest { Mode = Proto.FilterMode.BlockAll }).ResponseAsync), connected));
        }

        // 勿擾
        if (status?.DoNotDisturbUntil is not null)
        {
            menu.Items.Add(Item(loc.Get(TrayLangResourceKind.DoNotDisturbOff),
                () => _ = CallAsync(c => c.Control.SetDoNotDisturbAsync(new Proto.SetDoNotDisturbRequest()).ResponseAsync), connected));
        }
        else
        {
            menu.Items.Add(Item(loc.Get(TrayLangResourceKind.DoNotDisturb),
                () => _ = CallAsync(c => c.Control.SetDoNotDisturbAsync(new Proto.SetDoNotDisturbRequest { Duration = Duration.FromTimeSpan(TimeSpan.FromHours(1)) }).ResponseAsync), connected));
        }

        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(Item(loc.Get(TrayLangResourceKind.Exit), () => ExitRequested?.Invoke(this, EventArgs.Empty)));
        menu.Items.Add(Item(loc.Get(TrayLangResourceKind.ExitAll), () => ExitAllRequested?.Invoke(this, EventArgs.Empty)));
        return menu;
    }

    private static NativeMenuItem Item(string header, Action onClick, bool enabled = true)
    {
        var item = new NativeMenuItem(header) { IsEnabled = enabled };
        item.Click += (_, _) => onClick();
        return item;
    }

    private async Task CallAsync<T>(Func<IpcClients, Task<T>> call)
    {
        if (_connection.Clients is not { } clients)
        {
            return;
        }

        try
        {
            await call(clients);
        }
        catch (RpcException)
        {
            // 狀態推送會讓菜單回到服務端的真實狀態
        }
    }
}
