using System.ComponentModel;
using AtomUI.Controls.Primitives;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NetworkControl.App.Localization;
using NetworkControl.App.Services;

namespace NetworkControl.App.ViewModels;

/// <summary>主窗口：左側導航 + 內容區 + 底部狀態欄；服務不可用時內容區顯示診斷頁。</summary>
public sealed partial class ShellViewModel : LocalizedViewModel
{
    private readonly Dictionary<string, PageViewModel> _pages;
    private readonly ShellActivator _activator;
    private bool _suppressActivation;

    public ShellViewModel(
        ServiceConnection connection,
        IEnumerable<PageViewModel> pages,
        ServiceUnavailableViewModel serviceUnavailable,
        ShellActivator activator,
        NavigationService navigation,
        DisplayText text)
        : base(text)
    {
        _activator = activator;
        navigation.Requested += (_, request) => NavigateTo(request.Page, request.Parameter);
        Connection = connection;
        ServiceUnavailable = serviceUnavailable;
        Pages = pages.ToArray();
        _pages = Pages.ToDictionary(p => p.Key, StringComparer.Ordinal);
        CurrentPage = Pages[0];
        connection.PropertyChanged += OnConnectionChanged;
        connection.Connected += (_, _) => _ = CurrentPage.OnActivatedAsync();
    }

    public ServiceConnection Connection { get; }

    public ServiceUnavailableViewModel ServiceUnavailable { get; }

    public IReadOnlyList<PageViewModel> Pages { get; }

    [ObservableProperty]
    public partial PageViewModel CurrentPage { get; set; }

    /// <summary>當前頁的導航鍵（菜單項 ItemKey）。</summary>
    public string CurrentKey => CurrentPage.Key;

    /// <summary>導航菜單選中路徑，跟隨當前頁（包括從托盤、通知跳轉時）。</summary>
    public TreeNodePath SelectedMenuPath => new(CurrentPage.Key);

    public bool IsConnected => Connection.State == ConnectionState.Connected;

    public bool ShowServiceUnavailable => Connection.State == ConnectionState.Disconnected;

    public string ConnectionText => Loc.Get(Connection.State switch
    {
        ConnectionState.Connected => ShellLangResourceKind.StatusConnected,
        ConnectionState.Connecting => ShellLangResourceKind.StatusConnecting,
        _ => ShellLangResourceKind.StatusDisconnected,
    });

    public string ModeText => Connection.Status is { } status ? Text.Mode(status.Mode) : "";

    public string EngineText => Connection.Status?.Engine is { } engine ? Loc.Format(ShellLangResourceKind.StatusEngine, engine.Name) : "";

    /// <summary>按導航鍵切換頁面（導航菜單、托盤、通知都通過它跳轉）。</summary>
    [RelayCommand]
    public void Navigate(string key)
    {
        if (_pages.TryGetValue(key, out var page) && !ReferenceEquals(page, CurrentPage))
        {
            CurrentPage = page;
        }
    }

    /// <summary>切換到指定頁面並傳遞參數（例如連線記錄的篩選條件）。</summary>
    public void NavigateTo(string key, object? parameter)
    {
        if (!_pages.TryGetValue(key, out var page))
        {
            return;
        }

        _activator.Show();
        if (parameter is null)
        {
            Navigate(key);
            return;
        }

        var handled = page.ApplyNavigation(parameter);
        if (ReferenceEquals(page, CurrentPage))
        {
            if (!handled)
            {
                _ = page.OnActivatedAsync();
            }

            return;
        }

        _suppressActivation = handled;
        CurrentPage = page;
        _suppressActivation = false;
    }

    /// <summary>退出界面（不依賴托盤菜單也能退出）。</summary>
    [RelayCommand]
    private void Quit() => _activator.Quit();

    partial void OnCurrentPageChanged(PageViewModel oldValue, PageViewModel newValue)
    {
        // 首次賦值時 oldValue 為 null（生成代碼的簽名沒有標註可空）
        (oldValue as PageViewModel)?.OnDeactivated();
        OnPropertyChanged(nameof(CurrentKey));
        OnPropertyChanged(nameof(SelectedMenuPath));
        if (!_suppressActivation)
        {
            _ = newValue.OnActivatedAsync();
        }
    }

    private void OnConnectionChanged(object? sender, PropertyChangedEventArgs e)
    {
        OnPropertyChanged(nameof(IsConnected));
        OnPropertyChanged(nameof(ShowServiceUnavailable));
        OnPropertyChanged(nameof(ConnectionText));
        OnPropertyChanged(nameof(ModeText));
        OnPropertyChanged(nameof(EngineText));
    }
}
