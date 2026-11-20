using AtomUI.Theme;
using AtomUI.Theme.Algorithms;
using AtomUI.Theme.Configuration;
using AtomUI.Theme.Resources;
using Avalonia.Media;
using Avalonia.Platform;

namespace NetworkControl.Android.Infrastructure;

/// <summary>
/// 明暗外觀、種子強調色與 6 套視覺風格引擎：與 Kotlin 版 PocketGate 完全對齊。
/// </summary>
public sealed class AppearanceService(IThemeManager themes, UiPreferences preferences)
{
    private Avalonia.Application? _application;
    private IPlatformSettings? _platform;
    private bool? _appliedDark;
    private AppThemeStyle? _appliedStyle;
    private uint? _appliedSeed;

    public ThemePreference Preference => preferences.Theme;

    public AppThemeStyle ThemeStyle => preferences.ThemeStyle;

    public uint ThemeColorArgb => preferences.ThemeColorArgb;

    public static ThemeConfig InitialConfig(ThemePreference preference, AppThemeStyle style, bool systemDark) =>
        ConfigFor(ResolveDark(preference, style, systemDark));

    public Task InitializeAsync(Avalonia.Application application, bool initialDark)
    {
        _application = application;
        _appliedDark = initialDark;
        _appliedStyle = preferences.ThemeStyle;
        _appliedSeed = preferences.ThemeColorArgb;
        _platform = application.PlatformSettings;
        if (_platform is not null)
        {
            _platform.ColorValuesChanged += (_, _) =>
            {
                if (Preference == ThemePreference.System)
                {
                    _ = ApplyAsync(ThemePreference.System, ThemeStyle, ThemeColorArgb);
                }
            };
        }

        ApplyPaletteOverrides(initialDark, preferences.ThemeStyle, preferences.ThemeColorArgb);
        return ApplyAsync(Preference, ThemeStyle, ThemeColorArgb);
    }

    public Task SetPreferenceAsync(ThemePreference preference)
    {
        preferences.Theme = preference;
        return ApplyAsync(preference, ThemeStyle, ThemeColorArgb);
    }

    public Task SetThemeStyleAsync(AppThemeStyle style)
    {
        preferences.ThemeStyle = style;
        return ApplyAsync(Preference, style, ThemeColorArgb);
    }

    public Task SetThemeColorAsync(uint argb)
    {
        preferences.ThemeColorArgb = argb;
        return ApplyAsync(Preference, ThemeStyle, argb);
    }

    private async Task ApplyAsync(ThemePreference preference, AppThemeStyle style, uint seedArgb)
    {
        var systemDark = _platform?.GetColorValues().ThemeVariant == PlatformThemeVariant.Dark;
        var dark = ResolveDark(preference, style, systemDark);
        if (_appliedDark == dark && _appliedStyle == style && _appliedSeed == seedArgb)
        {
            return;
        }

        var themeChanged = _appliedDark != dark;
        _appliedDark = dark;
        _appliedStyle = style;
        _appliedSeed = seedArgb;
        if (themeChanged)
        {
            await themes.ApplyThemeAsync(new ThemeRequest(IThemeManager.DEFAULT_THEME_ID, ConfigFor(dark), ThemeTransitionReason.UserRequest)).ConfigureAwait(true);
        }

        ApplyPaletteOverrides(dark, style, seedArgb);
    }

    private void ApplyPaletteOverrides(bool dark, AppThemeStyle style, uint seedArgb)
    {
        if (_application?.Resources is not { } res)
        {
            return;
        }

        var primary = style switch
        {
            AppThemeStyle.ObsidianCyber => Color.FromUInt32(0xFFC9F45A),
            AppThemeStyle.AuroraGlass => Color.FromUInt32(0xFF38BDF8),
            _ => Color.FromUInt32(seedArgb),
        };
        var primaryBg = Color.FromArgb(dark ? (byte)0x30 : (byte)0x1E, primary.R, primary.G, primary.B);
        var primaryBorder = Color.FromArgb(0x66, primary.R, primary.G, primary.B);

        var (bgLayout, bgContainer, bgElevated, borderSec) = (style, dark) switch
        {
            (AppThemeStyle.ObsidianCyber, _) => (Color.FromUInt32(0xFF0B0F0D), Color.FromUInt32(0xFF141A17), Color.FromUInt32(0xFF1B231F), Color.FromUInt32(0xFF26332D)),
            (AppThemeStyle.AmoledBlack, _) => (Color.FromUInt32(0xFF000000), Color.FromUInt32(0xFF0C0C0E), Color.FromUInt32(0xFF16161A), Color.FromUInt32(0xFF232329)),
            (AppThemeStyle.FrostMinimal, _) => (Color.FromUInt32(0xFFF6F8FA), Color.FromUInt32(0xFFFFFFFF), Color.FromUInt32(0xFFFFFFFF), Color.FromUInt32(0xFFE5E9F0)),
            (AppThemeStyle.AuroraGlass, true) => (Color.FromUInt32(0xFF0D1422), Color.FromUInt32(0xCC162033), Color.FromUInt32(0xE61C2940), Color.FromUInt32(0xFF2A3B59)),
            (AppThemeStyle.AuroraGlass, false) => (Color.FromUInt32(0xFFEEF6FF), Color.FromUInt32(0xE6FFFFFF), Color.FromUInt32(0xF5FFFFFF), Color.FromUInt32(0xFFD6E4F5)),
            (_, true) => (Color.FromUInt32(0xFF121316), Color.FromUInt32(0xFF1C1E22), Color.FromUInt32(0xFF24272C), Color.FromUInt32(0xFF31353D)),
            (_, false) => (Color.FromUInt32(0xFFF5F6F8), Color.FromUInt32(0xFFFFFFFF), Color.FromUInt32(0xFFFFFFFF), Color.FromUInt32(0xFFE6E8EC)),
        };

        res[SharedTokenKind.ColorPrimary] = primary;
        res[SharedTokenKind.ColorPrimaryHover] = primary;
        res[SharedTokenKind.ColorPrimaryBg] = primaryBg;
        res[SharedTokenKind.ColorPrimaryBorder] = primaryBorder;
        res[SharedTokenKind.ColorBgLayout] = bgLayout;
        res[SharedTokenKind.ColorBgContainer] = bgContainer;
        res[SharedTokenKind.ColorBgElevated] = bgElevated;
        res[SharedTokenKind.ColorBorderSecondary] = borderSec;
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
