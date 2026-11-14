using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.Extensions.DependencyInjection;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Android.Platform;

namespace NetworkControl.Android.ViewModels;

/// <summary>主視圖：等待核心啟動，然後創建各頁面並轉發狀態變化。</summary>
public sealed partial class MainViewModel(LocalizationService localization, AppearanceService appearance) : ObservableObject
{
    private DispatcherTimer? _refreshDebounce;

    [ObservableProperty]
    public partial bool IsLoading { get; set; } = true;

    [ObservableProperty]
    public partial string? StartError { get; set; }

    /// <summary>0 總覽、1 應用程式、2 阻擋記錄、3 設定。</summary>
    [ObservableProperty]
    public partial int SelectedTab { get; set; }

    [ObservableProperty]
    public partial OverviewViewModel? Overview { get; set; }

    [ObservableProperty]
    public partial AppsViewModel? Apps { get; set; }

    [ObservableProperty]
    public partial LogViewModel? Log { get; set; }

    [ObservableProperty]
    public partial SettingsViewModel? Settings { get; set; }

    [ObservableProperty]
    public partial PromptViewModel? Prompt { get; set; }

    public bool IsOverview => SelectedTab == 0;

    public bool IsApps => SelectedTab == 1;

    public bool IsLog => SelectedTab == 2;

    public bool IsSettings => SelectedTab == 3;

    public async Task InitializeAsync()
    {
        IsLoading = true;
        StartError = null;
        try
        {
            var host = await BackendHost.EnsureStartedAsync(global::Android.App.Application.Context);
            var services = host.Services;
            var context = new UiContext(
                services.GetRequiredService<IBackend>(),
                localization,
                appearance,
                services.GetRequiredService<AppIconLoader>(),
                services.GetRequiredService<PromptNotifier>());

            Prompt = new PromptViewModel(context);
            Overview = new OverviewViewModel(context, () => Prompt.OpenFirst());
            Apps = new AppsViewModel(context);
            Log = new LogViewModel(context);
            Settings = new SettingsViewModel(context);

            context.Backend.Changed += (_, _) => Dispatcher.UIThread.Post(ScheduleRefresh);
            MainActivity.PromptRequested += (_, id) => Dispatcher.UIThread.Post(() => OpenPrompt(id));
            MainActivity.NavigationRequested += (_, request) => Dispatcher.UIThread.Post(() => NavigateFromNotification(context, request));
            MainActivity.ForegroundChanged += (_, foreground) =>
            {
                context.Notifier.IsUiForeground = foreground;
                Prompt.IsForeground = foreground;
                if (foreground)
                {
                    Dispatcher.UIThread.Post(RefreshAll);
                }
            };
            context.Notifier.IsUiForeground = true;

            Prompt.Start();
            RefreshAll();
            if (MainActivity.PendingPromptId is { } pending)
            {
                OpenPrompt(pending);
            }

            if (MainActivity.PendingNavigation is { } navigation)
            {
                NavigateFromNotification(context, navigation);
            }
        }
        catch (Exception ex)
        {
            StartError = localization.Format(ShellLangResourceKind.StartFailed, ex.Message);
        }
        finally
        {
            IsLoading = false;
        }
    }

    [RelayCommand]
    private void Navigate(string tab)
    {
        if (int.TryParse(tab, System.Globalization.CultureInfo.InvariantCulture, out var index))
        {
            SelectedTab = index;
        }
    }

    [RelayCommand]
    private Task RetryAsync() => InitializeAsync();

    partial void OnSelectedTabChanged(int value)
    {
        OnPropertyChanged(nameof(IsOverview));
        OnPropertyChanged(nameof(IsApps));
        OnPropertyChanged(nameof(IsLog));
        OnPropertyChanged(nameof(IsSettings));
        RefreshAll();
    }

    private void OpenPrompt(Guid promptId)
    {
        MainActivity.PendingPromptId = null;
        Prompt?.Open(promptId);
    }

    /// <summary>從通知跳轉頁面；勿擾摘要通知同時清除摘要（與桌面「查看」一致）。</summary>
    private void NavigateFromNotification(UiContext context, (int Tab, bool DismissQuietSummary) request)
    {
        MainActivity.PendingNavigation = null;
        SelectedTab = Math.Clamp(request.Tab, 0, 3);
        if (request.DismissQuietSummary)
        {
            context.Backend.DismissQuietPeriod();
            Overview?.Refresh();
        }
    }

    /// <summary>合併短時間內的多次變化（規則、模式、引擎狀態可能連續變化）。</summary>
    private void ScheduleRefresh()
    {
        _refreshDebounce ??= new DispatcherTimer(TimeSpan.FromMilliseconds(200), DispatcherPriority.Background, (_, _) =>
        {
            _refreshDebounce!.Stop();
            RefreshAll();
        });
        _refreshDebounce.Stop();
        _refreshDebounce.Start();
    }

    private void RefreshAll()
    {
        Overview?.Refresh();
        switch (SelectedTab)
        {
            case 1:
                Apps?.Refresh();
                break;
            case 2:
                _ = Log?.RefreshAsync();
                break;
            case 3:
                Settings?.Refresh();
                break;
        }
    }
}
