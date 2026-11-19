using AtomUI;
using AtomUI.Desktop.Controls;
using AtomUI.Theme;
using Avalonia;
using Avalonia.Android;
using global::Android.Content;
using global::Android.Content.Res;
using global::Android.Runtime;
using NetworkControl.Android.Infrastructure;

namespace NetworkControl.Android;

/// <summary>Android 進程入口：構建 Avalonia 應用並初始化 AtomUI（語言、首幀主題按用戶偏好）。</summary>
[global::Android.App.Application(AllowBackup = false, Label = "PocketGate (AtomUI)", Icon = "@mipmap/ic_launcher")]
public sealed class AndroidApp : AvaloniaAndroidApplication<App>
{
    public AndroidApp(IntPtr javaReference, JniHandleOwnership transfer)
        : base(javaReference, transfer)
    {
    }

    /// <summary>系統當前是否為夜間模式。</summary>
    public static bool SystemPrefersDark(Context context) =>
        context.Resources?.Configuration is { } config && (config.UiMode & UiMode.NightMask) == UiMode.NightYes;

    protected override AppBuilder CustomizeAppBuilder(AppBuilder builder)
    {
        var preferences = new UiPreferences(this);
        var language = SupportedLanguages.Resolve(preferences.Language);
        AndroidApplicationLocales.Apply(preferences.Language is null ? null : language);
        return base.CustomizeAppBuilder(builder)
            .WithAtomUIDefaultOptions()
            .UseAtomUI(atom =>
            {
                atom.UseLanguages(language, SupportedLanguages.All);
                atom.WithInitialTheme(IThemeManager.DEFAULT_THEME_ID, AppearanceService.InitialConfig(preferences.Theme, preferences.ThemeStyle, SystemPrefersDark(this)));
                // AtomUI 默認字體（Alibaba Sans + Microsoft YaHei）在 Android 上沒有中文字形，也不會回退到系統字體，中文顯示為方框：
                // 改用系統的 sans-serif（Roboto），中文由系統的 Noto Sans CJK 回退提供（見 AGENTS.md 第 6 節）
                atom.WithDefaultFontFamily("sans-serif");
                atom.UseDesktopControls();
            });
    }
}
