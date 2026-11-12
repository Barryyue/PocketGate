using AtomUI;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Microsoft.Extensions.DependencyInjection;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using NetworkControl.App.Services;
using NetworkControl.App.ViewModels;
using NetworkControl.App.Views;
using NetworkControl.Ipc;

namespace NetworkControl.App;

/// <summary>
/// 應用類。必須是 partial：AtomUI 生成器為它生成啟動掛接（主題、語言 Catalog 在 Initialize 之前提交）。
/// </summary>
public sealed partial class App : Application
{
    private readonly PreferencesStore _preferences;
    private ServiceProvider? _services;
    private ShellWindow? _shell;

    public App()
        : this(new PreferencesStore())
    {
    }

    public App(PreferencesStore preferences) => _preferences = preferences;

    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            // 托盤應用：關閉主窗口不退出，只有托盤菜單中的「結束介面」才結束進程
            desktop.ShutdownMode = ShutdownMode.OnExplicitShutdown;

            var services = _services = ConfigureServices();
            _ = services.GetRequiredService<AppearanceService>().InitializeAsync(this);
            var activator = services.GetRequiredService<ShellActivator>();
            activator.Activate = ShowShell;
            activator.QuitApplication = () => _ = QuitAsync(desktop, stopService: false);
            activator.QuitAll = () => _ = QuitAsync(desktop, stopService: true);

            // 這些組件訂閱連接事件，必須在連接啟動前創建，才能收到第一次 Connected
            services.GetRequiredService<PromptCoordinator>();
            services.GetRequiredService<BlockedNotifier>();

            var tray = services.GetRequiredService<TrayService>();
            tray.Attach(this);
            tray.NavigateRequested += (_, page) => ShowShell(page);
            tray.ExitRequested += (_, _) => activator.Quit();
            tray.ExitAllRequested += (_, _) => activator.QuitEverything();
            desktop.ShutdownRequested += (_, _) => _ = ShutdownAsync();

            StartDevelopmentServiceIfNeeded(services);
            services.GetRequiredService<ServiceConnection>().Start();

            // 開機自啟時以 --minimized 啟動，只顯示托盤
            var minimized = desktop.Args?.Contains("--minimized", StringComparer.OrdinalIgnoreCase) == true;
            if (!minimized)
            {
                ShowShell();
            }
        }

        base.OnFrameworkInitializationCompleted();
    }

    private ServiceProvider ConfigureServices()
    {
        var services = new ServiceCollection();

        // AtomUI 運行時對象：與控件共用同一實例，不另建管理器
        services.AddSingleton(_preferences);
        services.AddSingleton(this.GetLanguageManager() ?? throw new InvalidOperationException("AtomUI language manager is not available."));
        services.AddSingleton(this.GetLocalizer() ?? throw new InvalidOperationException("AtomUI localizer is not available."));
        services.AddSingleton(this.GetThemeManager() ?? throw new InvalidOperationException("AtomUI theme manager is not available."));

        // 界面基礎設施
        services.AddSingleton<LocalizationService>();
        services.AddSingleton<DisplayText>();
        services.AddSingleton<AppearanceService>();
        services.AddSingleton<WindowHost>();
        services.AddSingleton<FeedbackService>();
        services.AddSingleton<DialogService>();
        services.AddSingleton<ShellActivator>();
        services.AddSingleton<ToastService>();

        // 與服務交互
        services.AddSingleton<ServiceConnection>();
        services.AddSingleton<IServiceLauncher, ServiceLauncher>();
        services.AddSingleton<ServiceLifecycle>();
        services.AddSingleton<AutoStartService>();
        services.AddSingleton<RuleEditing>();
        services.AddSingleton<PromptCoordinator>();
        services.AddSingleton<BlockedNotifier>();
        services.AddSingleton<TrayService>();

        // 頁面：註冊順序即導航順序（與 ShellWindow 中的導航菜單一致）
        services.AddSingleton<PageViewModel, OverviewViewModel>();
        services.AddSingleton<PageViewModel, RulesViewModel>();
        services.AddSingleton<PageViewModel, LogViewModel>();
        services.AddSingleton<PageViewModel, SettingsViewModel>();
        services.AddSingleton<ServiceUnavailableViewModel>();
        services.AddSingleton<ShellViewModel>();

        return services.BuildServiceProvider(new ServiceProviderOptions { ValidateOnBuild = true, ValidateScopes = true });
    }

    private void ShowShell() => ShowShell(null);

    /// <summary>打開（或激活）主窗口，並可選地跳轉到指定頁面。</summary>
    private void ShowShell(string? page)
    {
        if (_services is null)
        {
            return;
        }

        if (_shell is null)
        {
            var shell = _services.GetRequiredService<ShellViewModel>();
            _shell = new ShellWindow(_services.GetRequiredService<WindowHost>()) { DataContext = shell };
            // 關閉主窗口時銷毀它（最小化到托盤），降低常駐內存（NFR-PERF-05）
            _shell.Closed += (_, _) => _shell = null;
            _shell.Show();
            _ = shell.CurrentPage.OnActivatedAsync();
        }
        else
        {
            _shell.WindowState = WindowState.Normal;
            _shell.Activate();
        }

        if (page is not null)
        {
            _services.GetRequiredService<ShellViewModel>().Navigate(page);
        }
    }

    /// <summary>
    /// 開發構建：服務還沒有運行、且旁邊有本地構建的服務時自動拉起它，
    /// 從 IDE 只啟動界面項目也能直接使用（發布構建不會自動啟動任何進程）。
    /// </summary>
    private static void StartDevelopmentServiceIfNeeded(IServiceProvider services)
    {
#if DEBUG
        var launcher = services.GetRequiredService<IServiceLauncher>();
        if (launcher.Kind == ServiceLaunchKind.Development && !IpcEndpoint.ResolveForClient().Any(e => e.MayBeAvailable))
        {
            _ = launcher.StartAsync(null, CancellationToken.None);
        }
#endif
    }

    /// <summary>
    /// 退出界面。<paramref name="stopService"/> 為 true 時完全退出：先讓核心服務退出（攔截停止）。
    /// 否則只退出界面：開發佈局下仍會關閉由界面拉起的開發服務，避免殘留後台進程；安裝版的系統服務繼續攔截。
    /// </summary>
    private async Task QuitAsync(IClassicDesktopStyleApplicationLifetime desktop, bool stopService)
    {
        if (_services?.GetService<ServiceLifecycle>() is { } lifecycle)
        {
            if (stopService)
            {
                await lifecycle.StopServiceAsync();
            }
            else
            {
                await lifecycle.ShutdownDevelopmentServiceAsync();
            }
        }

        desktop.Shutdown();
    }

    private async Task ShutdownAsync()
    {
        if (_services is not null)
        {
            await _services.DisposeAsync();
            _services = null;
        }
    }
}
