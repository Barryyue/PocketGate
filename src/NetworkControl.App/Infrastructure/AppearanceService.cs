using AtomUI.Theme;
using AtomUI.Theme.Algorithms;
using AtomUI.Theme.Configuration;
using Avalonia;
using Avalonia.Platform;
using Microsoft.Win32;

namespace NetworkControl.App.Infrastructure;

/// <summary>
/// 外觀（淺色 / 深色 / 跟隨系統）。由本服務統一負責「跟隨系統」：
/// AtomUI 內置的 FollowSystem 一旦在啟動時開啟，之後用戶手動選擇淺色或深色時仍會被系統外觀變化覆蓋，
/// 因此不使用它，而是在這裡監聽平台外觀並提交主題請求，保證只有一個寫入方。
/// </summary>
public sealed class AppearanceService
{
    private readonly IThemeManager _themes;
    private readonly PreferencesStore _preferences;
    private IPlatformSettings? _platform;
    private bool? _appliedDark;

    public AppearanceService(IThemeManager themes, PreferencesStore preferences)
    {
        _themes = themes;
        _preferences = preferences;
    }

    public ThemePreference Preference => _preferences.Current.Theme;

    /// <summary>啟動配置使用：在 Avalonia 初始化之前估計首幀外觀，避免深色系統上閃一下淺色。</summary>
    public static ThemeConfig InitialConfig(ThemePreference preference) =>
        ConfigFor(preference switch
        {
            ThemePreference.Dark => true,
            ThemePreference.Light => false,
            _ => SystemPrefersDarkBeforeStartup(),
        });

    /// <summary>應用框架初始化完成後調用：校正首幀外觀，並開始跟隨系統（如果需要）。</summary>
    public Task InitializeAsync(Application application)
    {
        _platform = application.PlatformSettings;
        if (_platform is not null)
        {
            _platform.ColorValuesChanged += OnPlatformColorsChanged;
        }

        _appliedDark = Preference switch
        {
            ThemePreference.Dark => true,
            ThemePreference.Light => false,
            _ => SystemPrefersDarkBeforeStartup(),
        };
        return ApplyAsync(Preference);
    }

    public Task SetPreferenceAsync(ThemePreference preference)
    {
        _preferences.Update(p => p with { Theme = preference });
        return ApplyAsync(preference);
    }

    private Task ApplyAsync(ThemePreference preference)
    {
        var dark = preference switch
        {
            ThemePreference.Dark => true,
            ThemePreference.Light => false,
            _ => _platform?.GetColorValues().ThemeVariant == PlatformThemeVariant.Dark,
        };

        // 外觀沒有變化（例如啟動時的估計正確、或在「跟隨系統」與相同明暗之間切換）時不提交重複請求
        if (_appliedDark == dark)
        {
            return Task.CompletedTask;
        }

        _appliedDark = dark;
        return _themes.ApplyThemeAsync(new ThemeRequest(IThemeManager.DEFAULT_THEME_ID, ConfigFor(dark), ThemeTransitionReason.UserRequest));
    }

    private void OnPlatformColorsChanged(object? sender, PlatformColorValues values)
    {
        if (Preference == ThemePreference.System)
        {
            _ = ApplyAsync(ThemePreference.System);
        }
    }

    private static ThemeConfig ConfigFor(bool dark) => dark
        ? new ThemeConfigBuilder().WithAlgorithms(ThemeAlgorithm.Default, ThemeAlgorithm.Dark).Build()
        : new ThemeConfigBuilder().WithAlgorithms(ThemeAlgorithm.Default).Build();

    /// <summary>Avalonia 初始化之前讀取系統外觀。目前只有 Windows 能提前讀到，其他平台在初始化後校正。</summary>
    private static bool SystemPrefersDarkBeforeStartup()
    {
        if (!OperatingSystem.IsWindows())
        {
            return false;
        }

        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("AppsUseLightTheme") is int light && light == 0;
        }
        catch (System.Security.SecurityException)
        {
            return false;
        }
    }
}
