using AtomUI;
using AtomUI.Desktop.Controls;
using AtomUI.Theme;
using Avalonia;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Services;

namespace NetworkControl.App;

internal static class Program
{
    // 在 AppMain 之前不要使用任何 Avalonia API 或依賴 SynchronizationContext 的代碼
    [STAThread]
    public static int Main(string[] args)
    {
        AppLog.Initialize(args);
        if (CommandLineExport.IsRequested(args))
        {
            return CommandLineExport.Run(args);
        }

        using var instance = SingleInstance.TryAcquire();
        if (instance is null)
        {
            // 已有實例在運行：讓它顯示主窗口，本進程退出（開機自啟的 --minimized 不喚起）
            if (!args.Contains("--minimized", StringComparer.OrdinalIgnoreCase))
            {
                SingleInstance.SignalExisting(TimeSpan.FromSeconds(2));
            }

            return 0;
        }

        return BuildAvaloniaApp(new PreferencesStore()).StartWithClassicDesktopLifetime(args);
    }

    // 可視化設計器會調用無參數版本，不要刪除
    public static AppBuilder BuildAvaloniaApp() => BuildAvaloniaApp(new PreferencesStore());

    private static AppBuilder BuildAvaloniaApp(PreferencesStore preferences)
    {
        var builder = AppBuilder.Configure(() => new App(preferences))
            .UseAtomUIPlatformDetect()
            .WithAtomUIDefaultOptions()
            .UseAtomUI(atom =>
            {
                // 啟動語言：用戶偏好 → 系統界面語言 → en-US；支持語言集合決定構建時的翻譯完整性校驗
                atom.UseLanguages(SupportedLanguages.Resolve(preferences.Current.Language), SupportedLanguages.All);
                // 首幀外觀按偏好估計，框架初始化後由 AppearanceService 校正並負責跟隨系統
                atom.WithInitialTheme(IThemeManager.DEFAULT_THEME_ID, AppearanceService.InitialConfig(preferences.Current.Theme));
                atom.UseDesktopControls();
                atom.UseDesktopDataGrid();
            });
#if DEBUG
        builder = builder.WithDeveloperTools();
#endif
        // 框架警告（綁定失敗等）寫入界面日誌文件，診斷包會收集
        Avalonia.Logging.Logger.Sink = new AppLogSink(Avalonia.Logging.LogEventLevel.Warning);
        return builder;
    }
}
