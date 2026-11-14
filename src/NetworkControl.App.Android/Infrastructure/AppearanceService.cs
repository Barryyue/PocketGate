using AtomUI.Theme;
using AtomUI.Theme.Algorithms;
using AtomUI.Theme.Configuration;
using Avalonia.Platform;

namespace NetworkControl.Android.Infrastructure;

/// <summary>
/// 明暗外觀：跟隨系統 / 淺色 / 深色。與桌面版一樣自己實現跟隨系統（AtomUI 的 FollowSystem 會覆蓋用戶的手動選擇）。
/// </summary>
public sealed class AppearanceService(IThemeManager themes, UiPreferences preferences)
{
    private IPlatformSettings? _platform;
    private bool? _appliedDark;

    public ThemePreference Preference => preferences.Theme;

    /// <summary>Avalonia 初始化之前按系統的夜間模式估計首幀外觀。</summary>
    public static ThemeConfig InitialConfig(ThemePreference preference, bool systemDark) => ConfigFor(preference switch
    {
        ThemePreference.Dark => true,
        ThemePreference.Light => false,
        _ => systemDark,
    });

    public Task InitializeAsync(Avalonia.Application application, bool initialDark)
    {
        _appliedDark = initialDark;
        _platform = application.PlatformSettings;
        if (_platform is not null)
        {
            _platform.ColorValuesChanged += (_, _) =>
            {
                if (Preference == ThemePreference.System)
                {
                    _ = ApplyAsync(ThemePreference.System);
                }
            };
        }

        return ApplyAsync(Preference);
    }

    public Task SetPreferenceAsync(ThemePreference preference)
    {
        preferences.Theme = preference;
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

        if (_appliedDark == dark)
        {
            return Task.CompletedTask;
        }

        _appliedDark = dark;
        return themes.ApplyThemeAsync(new ThemeRequest(IThemeManager.DEFAULT_THEME_ID, ConfigFor(dark), ThemeTransitionReason.UserRequest));
    }

    private static ThemeConfig ConfigFor(bool dark) => dark
        ? new ThemeConfigBuilder().WithAlgorithms(ThemeAlgorithm.Default, ThemeAlgorithm.Dark).Build()
        : new ThemeConfigBuilder().WithAlgorithms(ThemeAlgorithm.Default).Build();
}
