using System.Globalization;
using AtomUI.Localization;

namespace NetworkControl.App.Infrastructure;

/// <summary>支持的界面語言。新增語言時：在這裡加標籤，並為每個 Catalog 添加對應的 XLIFF 文件（構建會校驗完整性）。</summary>
public static class SupportedLanguages
{
    public static readonly LanguageTag[] All = [LanguageTags.ZhCN, LanguageTags.ZhTW, LanguageTags.EnUS];

    /// <summary>
    /// 解析啟動語言：用戶偏好 → 系統界面語言（繁體地區 → zh-TW，其他中文 → zh-CN）→ en-US。
    /// </summary>
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
/// 界面本地化入口：封裝 AtomUI 的 <see cref="ILanguageManager"/> 與 <see cref="ILocalizer"/>。
/// XAML 中的文本用生成的 <c>{loc:XxxLangResource Key}</c> 自動響應語言切換；
/// 視圖模型中計算出的文本需要訂閱 <see cref="LanguageChanged"/> 後重新通知屬性變化。
/// </summary>
public sealed class LocalizationService
{
    private readonly ILanguageManager _manager;
    private readonly ILocalizer _localizer;
    private readonly PreferencesStore _preferences;

    public LocalizationService(ILanguageManager manager, ILocalizer localizer, PreferencesStore preferences)
    {
        _manager = manager;
        _localizer = localizer;
        _preferences = preferences;
        _manager.LanguageChanged += (_, _) => LanguageChanged?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>語言切換後觸發（UI 線程）。</summary>
    public event EventHandler? LanguageChanged;

    public LanguageTag Current => _manager.Current.CurrentLanguage;

    /// <summary>當前語言的格式化區域（日期、數字）。</summary>
    public CultureInfo Culture => _manager.Current.FormattingCulture;

    public IReadOnlyList<LanguageDefinition> Languages => _manager.SupportedLanguages;

    /// <summary>用戶偏好中的語言；為 null 表示跟隨系統。</summary>
    public string? Preference => _preferences.Current.Language;

    public string Get<TKind>(TKind key)
        where TKind : struct, Enum => _localizer.Get(key);

    public string Format<TKind>(TKind key, params object?[] arguments)
        where TKind : struct, Enum => _localizer.Format(key, arguments);

    /// <summary>切換語言並保存偏好；<paramref name="language"/> 為 null 表示跟隨系統。必須在 UI 線程調用。</summary>
    public void SetPreference(LanguageTag? language)
    {
        _preferences.Update(p => p with { Language = language?.ToString() });
        _manager.ChangeLanguage(language ?? SupportedLanguages.Resolve(null));
    }
}
