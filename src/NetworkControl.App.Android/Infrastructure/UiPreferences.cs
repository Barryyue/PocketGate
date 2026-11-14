using global::Android.Content;

namespace NetworkControl.Android.Infrastructure;

/// <summary>主題偏好。</summary>
public enum ThemePreference
{
    System = 0,
    Light = 1,
    Dark = 2,
}

/// <summary>界面偏好（語言、主題），保存在 SharedPreferences 中。核心的設置（詢問超時等）在 config.db 中。</summary>
public sealed class UiPreferences
{
    private const string PreferencesName = "networkcontrol.ui";
    private const string LanguageKey = "language";
    private const string ThemeKey = "theme";

    private readonly ISharedPreferences _preferences;

    public UiPreferences(Context context)
    {
        _preferences = context.GetSharedPreferences(PreferencesName, FileCreationMode.Private)
            ?? throw new InvalidOperationException("SharedPreferences 不可用。");
    }

    /// <summary>語言標籤（如 zh-TW）；為 null 表示跟隨系統。</summary>
    public string? Language
    {
        get => _preferences.GetString(LanguageKey, null);
        set => Edit(e => e.PutString(LanguageKey, value));
    }

    public ThemePreference Theme
    {
        get => (ThemePreference)_preferences.GetInt(ThemeKey, (int)ThemePreference.System);
        set => Edit(e => e.PutInt(ThemeKey, (int)value));
    }

    private void Edit(Action<ISharedPreferencesEditor> change)
    {
        using var editor = _preferences.Edit() ?? throw new InvalidOperationException("SharedPreferences 不可寫。");
        change(editor);
        editor.Apply();
    }
}
