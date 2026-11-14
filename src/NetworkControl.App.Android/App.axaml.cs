using AtomUI;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Threading;
using Microsoft.Extensions.DependencyInjection;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.ViewModels;
using NetworkControl.Android.Views;

namespace NetworkControl.Android;

/// <summary>
/// Avalonia 應用類。必須是 partial：AtomUI 生成器為它生成啟動掛接（主題、語言 Catalog 在 Initialize 之前提交）。
/// </summary>
public sealed partial class App : Avalonia.Application
{
    private MainViewModel? _main;

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
        TouchAdapters.Register();
    }

    public override void OnFrameworkInitializationCompleted()
    {
        switch (ApplicationLifetime)
        {
            case IActivityApplicationLifetime activity:
                activity.MainViewFactory = CreateMainView;
                break;
            case ISingleViewApplicationLifetime single:
                single.MainView = CreateMainView();
                break;
        }

        base.OnFrameworkInitializationCompleted();
    }

    /// <summary>每次 Activity 重建時調用；視圖模型和核心在進程內只創建一次。</summary>
    private ShellView CreateMainView()
    {
        if (_main is null)
        {
            Dispatcher.UIThread.UnhandledException += (_, e) =>
                global::Android.Util.Log.Error(Hosting.LogcatLoggerProvider.Tag, $"界面未處理的異常：{e.Exception}");

            var context = global::Android.App.Application.Context;
            var preferences = new UiPreferences(context);
            var localization = new LocalizationService(
                this.GetLanguageManager() ?? throw new InvalidOperationException("AtomUI language manager is not available."),
                this.GetLocalizer() ?? throw new InvalidOperationException("AtomUI localizer is not available."),
                preferences);
            // 通知文本來自同一組 Catalog：語言切換後刷新渠道名稱、前台通知和已顯示的詢問
            localization.LanguageChanged += (_, _) =>
                Hosting.BackendHost.Current?.Services.GetService<Hosting.IBackend>()?.RefreshNotifications();
            var appearance = new AppearanceService(
                this.GetThemeManager() ?? throw new InvalidOperationException("AtomUI theme manager is not available."),
                preferences);
            var initialDark = preferences.Theme switch
            {
                ThemePreference.Dark => true,
                ThemePreference.Light => false,
                _ => AndroidApp.SystemPrefersDark(context),
            };
            _ = appearance.InitializeAsync(this, initialDark);

            _main = new MainViewModel(localization, appearance);
            _ = _main.InitializeAsync();
        }

        return new ShellView { DataContext = _main };
    }
}
