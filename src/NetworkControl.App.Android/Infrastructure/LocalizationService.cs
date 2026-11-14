using System.Globalization;
using AtomUI.Localization;
using Avalonia.Threading;

namespace NetworkControl.Android.Infrastructure;

/// <summary>支持的界面語言（與桌面版相同）。</summary>
public static class SupportedLanguages
{
    public static readonly LanguageTag[] All = [LanguageTags.ZhTW, LanguageTags.ZhCN, LanguageTags.EnUS];

    /// <summary>用戶偏好 → 系統語言（繁體地區 → zh-TW，其他中文 → zh-CN）→ en-US。</summary>
    public static LanguageTag Resolve(string? preference, CultureInfo? systemCulture = null)
    {
        if (preference is not null && LanguageTag.TryParse(preference, out var preferred) && All.Contains(preferred))
        {
            return preferred;
        }

        var culture = systemCulture ?? CultureInfo.CurrentUICulture;
        for (var c = culture; !string.IsNullOrEmpty(c.Name); c = c.Parent)
        {
            switch (c.Name)
            {
                case "zh-Hant" or "zh-TW" or "zh-HK" or "zh-MO":
                    return LanguageTags.ZhTW;
                case "zh-Hans" or "zh-CN" or "zh-SG" or "zh":
                    return LanguageTags.ZhCN;
            }
        }

        return LanguageTags.EnUS;
    }
}

/// <summary>
/// 界面本地化入口：XAML 中用 <c>{loc:XxxLangResource Key}</c>；視圖模型中計算的文本調用 <see cref="Get{TKind}"/>，
/// 並在 <see cref="LanguageChanged"/> 後重新通知屬性變化。
/// </summary>
public sealed class LocalizationService
{
    private readonly ILanguageManager _manager;
    private readonly ILocalizer _localizer;
    private readonly UiPreferences _preferences;

    public LocalizationService(ILanguageManager manager, ILocalizer localizer, UiPreferences preferences)
    {
        _manager = manager;
        _localizer = localizer;
        _preferences = preferences;
        _manager.LanguageChanged += (_, _) => LanguageChanged?.Invoke(this, EventArgs.Empty);
    }

    public event EventHandler? LanguageChanged;

    public CultureInfo Culture => _manager.Current.FormattingCulture;

    /// <summary>偏好中的語言；為 null 表示跟隨系統。</summary>
    public string? Preference => _preferences.Language;

    public string Get<TKind>(TKind key)
        where TKind : struct, Enum => _localizer.Get(key);

    public string Format<TKind>(TKind key, params object?[] arguments)
        where TKind : struct, Enum => _localizer.Format(key, arguments);

    public void SetPreference(LanguageTag? language)
    {
        Dispatcher.UIThread.VerifyAccess();
        _preferences.Language = language?.ToString();
        _manager.ChangeLanguage(language ?? SupportedLanguages.Resolve(null));

        // 同步給系統的「應用程式語言」（API 33+）；API 29–32 跳過
        AndroidApplicationLocales.Apply(language);
    }
}
