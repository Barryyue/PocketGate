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
[global::Android.App.Application(AllowBackup = false, Label = "NetworkControl", Icon = "@mipmap/ic_launcher")]
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
        return base.CustomizeAppBuilder(builder)
            .WithAtomUIDefaultOptions()
            .UseAtomUI(atom =>
            {
                atom.UseLanguages(SupportedLanguages.Resolve(preferences.Language), SupportedLanguages.All);
                atom.WithInitialTheme(IThemeManager.DEFAULT_THEME_ID, AppearanceService.InitialConfig(preferences.Theme, SystemPrefersDark(this)));
                atom.UseDesktopControls();
            });
    }
}
