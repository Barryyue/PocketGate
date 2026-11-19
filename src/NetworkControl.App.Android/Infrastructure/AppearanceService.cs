using AtomUI.Theme;
using AtomUI.Theme.Algorithms;
using AtomUI.Theme.Configuration;
using Avalonia.Platform;

namespace NetworkControl.Android.Infrastructure;

/// <summary>
/// 明暗外觀與 6 套視覺風格引擎：與桌面版一樣自己實現跟隨系統，避免 AtomUI FollowSystem 覆蓋手動選擇。
/// </summary>
public sealed class AppearanceService(IThemeManager themes, UiPreferences preferences)
{
    private IPlatformSettings? _platform;
    private bool? _appliedDark;
    private AppThemeStyle? _appliedStyle;

    public ThemePreference Preference => preferences.Theme;

    public AppThemeStyle ThemeStyle => preferences.ThemeStyle;

    public static ThemeConfig InitialConfig(ThemePreference preference, AppThemeStyle style, bool systemDark) =>
        ConfigFor(ResolveDark(preference, style, systemDark));

    public Task InitializeAsync(Avalonia.Application application, bool initialDark)
    {
        _appliedDark = initialDark;
        _appliedStyle = preferences.ThemeStyle;
        _platform = application.PlatformSettings;
        if (_platform is not null)
        {
            _platform.ColorValuesChanged += (_, _) =>
            {
                if (Preference == ThemePreference.System)
                {
                    _ = ApplyAsync(ThemePreference.System, ThemeStyle);
                }
            };
        }

        return ApplyAsync(Preference, ThemeStyle);
    }

    public Task SetPreferenceAsync(ThemePreference preference)
    {
        preferences.Theme = preference;
        return ApplyAsync(preference, ThemeStyle);
    }

    public Task SetThemeStyleAsync(AppThemeStyle style)
    {
        preferences.ThemeStyle = style;
        return ApplyAsync(Preference, style);
    }

    private Task ApplyAsync(ThemePreference preference, AppThemeStyle style)
    {
        var systemDark = _platform?.GetColorValues().ThemeVariant == PlatformThemeVariant.Dark;
        var dark = ResolveDark(preference, style, systemDark);
        if (_appliedDark == dark && _appliedStyle == style)
        {
            return Task.CompletedTask;
        }

        _appliedDark = dark;
        _appliedStyle = style;
        return themes.ApplyThemeAsync(new ThemeRequest(IThemeManager.DEFAULT_THEME_ID, ConfigFor(dark), ThemeTransitionReason.UserRequest));
    }

    private static bool ResolveDark(ThemePreference preference, AppThemeStyle style, bool systemDark) => style switch
    {
        AppThemeStyle.ObsidianCyber or AppThemeStyle.AmoledBlack => true,
        AppThemeStyle.FrostMinimal => false,
        _ => preference switch
        {
            ThemePreference.Dark => true,
            ThemePreference.Light => false,
            _ => systemDark,
        },
    };

    private static ThemeConfig ConfigFor(bool dark) => dark
        ? new ThemeConfigBuilder().WithAlgorithms(ThemeAlgorithm.Default, ThemeAlgorithm.Dark).Build()
        : new ThemeConfigBuilder().WithAlgorithms(ThemeAlgorithm.Default).Build();
}
