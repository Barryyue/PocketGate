using AtomUI.Localization;
using global::Android.App;
using global::Android.Content;
using global::Android.OS;

namespace NetworkControl.Android.Infrastructure;

/// <summary>
/// 把應用內語言同步給系統（Android 13+ 的「應用程式語言」設定，docs/m3-tasks.md M3-10）。
/// API 29–32 沒有這個機制，跳過（通知文本仍由 <see cref="NotificationText"/> 按應用內語言提供）。
/// </summary>
internal static class AndroidApplicationLocales
{
    /// <summary><paramref name="language"/> 為 null 表示跟隨系統。</summary>
    public static void Apply(LanguageTag? language)
    {
        if (!OperatingSystem.IsAndroidVersionAtLeast(33))
        {
            return;
        }

        try
        {
            if (global::Android.App.Application.Context.GetSystemService(Context.LocaleService) is not LocaleManager manager)
            {
                return;
            }

            manager.ApplicationLocales = language is null
                ? LocaleList.EmptyLocaleList
                : LocaleList.ForLanguageTags(language.ToString());
        }
        catch (Java.Lang.Exception)
        {
            // 個別 ROM 不支援按應用設定語言：不影響應用內語言和通知文本
        }
    }
}
