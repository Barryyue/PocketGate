using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.Extensions.DependencyInjection;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Android.Platform;
using NetworkControl.Engine.Vpn.Update;

namespace NetworkControl.Android.ViewModels;

/// <summary>主視圖：等待核心啟動，然後創建各頁面並轉發狀態變化。</summary>
public sealed partial class MainViewModel(LocalizationService localization, AppearanceService appearance) : ObservableObject
{
    private DispatcherTimer? _refreshDebounce;
    private IBackend? _backend;
    private double _scrollAccum;

    [ObservableProperty]
    public partial bool IsLoading { get; set; } = true;

    [ObservableProperty]
    public partial string? StartError { get; set; }

    /// <summary>0 總覽、1 應用程式、2 規則、3 阻擋記錄、4 設定。</summary>
    [ObservableProperty]
    public partial int SelectedTab { get; set; }

    [ObservableProperty]
    public partial OverviewViewModel? Overview { get; set; }

    [ObservableProperty]
    public partial AppsViewModel? Apps { get; set; }

    [ObservableProperty]
    public partial RulesViewModel? Rules { get; set; }

    [ObservableProperty]
    public partial LogViewModel? Log { get; set; }

    [ObservableProperty]
    public partial SettingsViewModel? Settings { get; set; }

    [ObservableProperty]
    public partial PromptViewModel? Prompt { get; set; }

    [ObservableProperty]
    public partial bool BottomBarVisible { get; set; } = true;

    [ObservableProperty]
    public partial bool ShowUpdateAvailableDialog { get; set; }

    [ObservableProperty]
    public partial string UpdateAvailableTitle { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string UpdateAvailableBody { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string UpdateReleaseNotes { get; set; } = string.Empty;

    public bool HasUpdateReleaseNotes => !string.IsNullOrWhiteSpace(UpdateReleaseNotes);

    [ObservableProperty]
    public partial bool ShowUpdateReadyDialog { get; set; }

    [ObservableProperty]
    public partial string UpdateReadyTitle { get; set; } = string.Empty;

    [ObservableProperty]
    public partial bool ShowPermissionGuide { get; set; }

    [ObservableProperty]
    public partial PermissionHealthSnapshot? PermissionHealth { get; set; }

    public bool IsOverview => SelectedTab == 0;

    public bool IsApps => SelectedTab == 1;

    public bool IsRules => SelectedTab == 2;

    public bool IsLog => SelectedTab == 3;

    public bool IsSettings => SelectedTab == 4;

    public async Task InitializeAsync()
    {
        IsLoading = true;
        StartError = null;
        try
        {
            var host = await BackendHost.EnsureStartedAsync(global::Android.App.Application.Context);
            var services = host.Services;
            var backend = services.GetRequiredService<IBackend>();
            _backend = backend;
            var context = new UiContext(
                backend,
                localization,
                appearance,
                services.GetRequiredService<AppIconLoader>(),
                services.GetRequiredService<PromptNotifier>());

            Prompt = new PromptViewModel(context);
            Overview = new OverviewViewModel(context, () => Prompt.OpenFirst(), () => ShowPermissionGuide = true);
            Apps = new AppsViewModel(context);
            Rules = new RulesViewModel(context);
            Log = new LogViewModel(context);
            Settings = new SettingsViewModel(context);

            backend.UpdateManager.StateChanged += state => Dispatcher.UIThread.Post(() => ApplyUpdateState(state));
            backend.Changed += (_, _) => Dispatcher.UIThread.Post(ScheduleRefresh);
            MainActivity.PromptRequested += (_, id) => Dispatcher.UIThread.Post(() => OpenPrompt(id));
            MainActivity.NavigationRequested += (_, request) => Dispatcher.UIThread.Post(() => NavigateFromNotification(context, request));
            MainActivity.ForegroundChanged += (_, foreground) =>
            {
                context.Notifier.IsUiForeground = foreground;
                Prompt.IsForeground = foreground;
                if (foreground)
                {
                    Dispatcher.UIThread.Post(() =>
                    {
                        RefreshAll();
                        RefreshPermissions();
                        backend.UpdateManager.CheckOnEnter();
                    });
                }
            };
            context.Notifier.IsUiForeground = true;

            Prompt.Start();
            RefreshAll();
            RefreshPermissions();
            if (!backend.Preferences.PermissionGuideShown && (PermissionHealth?.MissingGuideCount ?? 0) > 0)
            {
                backend.Preferences.PermissionGuideShown = true;
                ShowPermissionGuide = true;
            }

            backend.UpdateManager.CheckOnEnter();
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
        BottomBarVisible = true;
        _scrollAccum = 0;
        OnPropertyChanged(nameof(IsOverview));
        OnPropertyChanged(nameof(IsApps));
        OnPropertyChanged(nameof(IsRules));
        OnPropertyChanged(nameof(IsLog));
        OnPropertyChanged(nameof(IsSettings));
        RefreshAll();
    }

    public void ReportScrollDelta(double dy)
    {
        if (_backend is not null && !_backend.Preferences.AutoHideBottomBar)
        {
            BottomBarVisible = true;
            return;
        }

        if (dy > 0)
        {
            if (_scrollAccum < 0) _scrollAccum = 0;
            _scrollAccum += dy;
            if (_scrollAccum >= 28)
            {
                BottomBarVisible = false;
                _scrollAccum = 0;
            }
        }
        else if (dy < 0)
        {
            if (_scrollAccum > 0) _scrollAccum = 0;
            _scrollAccum += dy;
            if (_scrollAccum <= -14)
            {
                BottomBarVisible = true;
                _scrollAccum = 0;
            }
        }
    }

    [RelayCommand]
    private void AcceptUpdate()
    {
        ShowUpdateAvailableDialog = false;
        _backend?.UpdateManager.StartDownload();
    }

    [RelayCommand]
    private void DismissUpdate()
    {
        ShowUpdateAvailableDialog = false;
        _backend?.UpdateManager.DismissAvailablePrompt();
    }

    [RelayCommand]
    private void InstallUpdateNow()
    {
        ShowUpdateReadyDialog = false;
        _backend?.UpdateManager.DismissReadyPrompt();
        if (_backend?.UpdateManager.State.DownloadedPath is { } path)
        {
            ApkInstaller.Launch(global::Android.App.Application.Context, path);
        }
    }

    [RelayCommand]
    private void DismissUpdateReady()
    {
        ShowUpdateReadyDialog = false;
        _backend?.UpdateManager.DismissReadyPrompt();
    }

    [RelayCommand]
    private void DismissPermissionGuide() => ShowPermissionGuide = false;

    [RelayCommand]
    private void OpenPermNotifications() => AndroidPermissionHelper.OpenNotificationSettings(global::Android.App.Application.Context);

    [RelayCommand]
    private void OpenPermBattery() => AndroidPermissionHelper.OpenBatteryOptimizationSettings(global::Android.App.Application.Context);

    [RelayCommand]
    private void OpenPermAppList() => AndroidPermissionHelper.OpenAppSettings(global::Android.App.Application.Context);

    [RelayCommand]
    private void OpenPermOverlay() => AndroidPermissionHelper.OpenOverlaySettings(global::Android.App.Application.Context);

    [RelayCommand]
    private void OpenPermUsage()
    {
        if (_backend is not null)
        {
            var intent = _backend.CreateTrafficAccessIntent().AddFlags(global::Android.Content.ActivityFlags.NewTask);
            global::Android.App.Application.Context.StartActivity(intent);
        }
    }

    private void RefreshPermissions()
    {
        if (_backend is null) return;
        PermissionHealth = _backend.CheckPermissionHealth();
        Overview?.UpdatePermissionHealth(PermissionHealth);
        Settings?.UpdatePermissionHealth(PermissionHealth);
    }

    private void ApplyUpdateState(AppUpdateState state)
    {
        Overview?.ApplyUpdateState(state);
        Settings?.ApplyUpdateState(state);
        if (state.ShowAvailablePrompt && state.Info is { HasUpdate: true } info)
        {
            var latest = info.LatestVersion ?? "";
            var current = global::Android.App.Application.Context.PackageManager?.GetPackageInfo(global::Android.App.Application.Context.PackageName!, 0)?.VersionName ?? "0.3.0";
            UpdateAvailableTitle = localization.Format(OverviewLangResourceKind.UpdateAvailableTitle, latest);
            UpdateAvailableBody = localization.Format(OverviewLangResourceKind.UpdateAvailableBody, current);
            UpdateReleaseNotes = info.ReleaseNotes ?? string.Empty;
            OnPropertyChanged(nameof(HasUpdateReleaseNotes));
            ShowUpdateAvailableDialog = true;
        }
        else
        {
            ShowUpdateAvailableDialog = false;
        }

        if (state.ShowReadyPrompt && state.DownloadedPath is not null)
        {
            UpdateReadyTitle = localization.Format(OverviewLangResourceKind.UpdateReady, state.Info?.LatestVersion ?? "");
            ShowUpdateReadyDialog = true;
        }
        else
        {
            ShowUpdateReadyDialog = false;
        }
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
        var mappedTab = request.Tab switch
        {
            2 => 3, // Log moved to index 3
            3 => 4, // Settings moved to index 4
            _ => Math.Clamp(request.Tab, 0, 4),
        };
        SelectedTab = mappedTab;
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
                Rules?.Refresh();
                break;
            case 3:
                _ = Log?.RefreshAsync();
                break;
            case 4:
                Settings?.Refresh();
                break;
        }
    }
}
