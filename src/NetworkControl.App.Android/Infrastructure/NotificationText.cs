using System.Globalization;
using System.Xml.Linq;
using AtomUI.Localization;
using global::Android.Content;

namespace NetworkControl.Android.Infrastructure;

/// <summary>
/// 通知等進程外文本的唯一入口（docs/m3-tasks.md M3-10）：直接讀取 tools/android-strings.py 生成的 XLIFF，
/// 與 AtomUI Catalog 同源，但不依賴 Avalonia 已初始化——開機啟動、只有 VPN 服務沒有界面時也能取得當前語言文本。
/// XLIFF 由 <c>NetworkControl.App.Android.csproj</c> 以嵌入資源打包；找不到某個鍵時退回 en-US，再退回鍵名。
/// </summary>
public sealed class NotificationText
{
    private readonly Dictionary<(string Catalog, string Language), IReadOnlyDictionary<string, string>> _cache = [];
    private readonly Lock _gate = new();

    /// <summary>進程級單例；沒有界面時也能使用。</summary>
    public static NotificationText Current { get; } = new();

    /// <summary>按當前應用內語言（SharedPreferences，未設置時跟隨系統）取得文本。</summary>
    public string Get<TKind>(Context context, TKind key)
        where TKind : struct, Enum =>
        Get(context, CatalogOf<TKind>(), Enum.GetName(key) ?? key.ToString());

    /// <summary>取得文本並用當前語言格式化（<c>{0}</c> 等佔位符）。</summary>
    public string Format<TKind>(Context context, TKind key, params object?[] arguments)
        where TKind : struct, Enum =>
        string.Format(CultureOf(context), Get(context, key), arguments);

    private string Get(Context context, string catalog, string id)
    {
        var language = LanguageOf(context).ToString();
        if (Load(catalog, language).TryGetValue(id, out var value))
        {
            return value;
        }

        return Load(catalog, "en-US").TryGetValue(id, out var fallback) ? fallback : id;
    }

    private LanguageTag LanguageOf(Context context) =>
        SupportedLanguages.Resolve(new UiPreferences(context).Language, SystemCulture(context));

    private CultureInfo CultureOf(Context context) => CultureInfo.GetCultureInfo(LanguageOf(context).ToString());

    /// <summary>系統界面語言（共享偏好為「跟隨系統」時使用）。</summary>
    private static CultureInfo SystemCulture(Context context)
    {
        try
        {
            var locales = context.Resources?.Configuration?.Locales;
            if (locales is { IsEmpty: false } && locales.Get(0)?.ToLanguageTag() is { Length: > 0 } tag)
            {
                return CultureInfo.GetCultureInfo(tag);
            }
        }
        catch (Exception ex) when (ex is Java.Lang.Exception or ArgumentException)
        {
            // 取不到系統語言：退回 .NET 的當前界面語言
        }

        return CultureInfo.CurrentUICulture;
    }

    private IReadOnlyDictionary<string, string> Load(string catalog, string language)
    {
        var key = (catalog, language);
        lock (_gate)
        {
            if (_cache.TryGetValue(key, out var cached))
            {
                return cached;
            }

            var units = Parse(catalog, language);
            _cache[key] = units;
            return units;
        }
    }

    private static IReadOnlyDictionary<string, string> Parse(string catalog, string language)
    {
        var assembly = typeof(NotificationText).Assembly;
        var suffix = $".Localization.{catalog}.{language}.xlf";
        var name = Array.Find(assembly.GetManifestResourceNames(), n =>
            n.Replace('\\', '.').EndsWith(suffix, StringComparison.OrdinalIgnoreCase));
        if (name is null)
        {
            return new Dictionary<string, string>(StringComparer.Ordinal);
        }

        using var stream = assembly.GetManifestResourceStream(name);
        if (stream is null)
        {
            return new Dictionary<string, string>(StringComparer.Ordinal);
        }

        var document = XDocument.Load(stream);
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var unit in document.Descendants().Where(e => e.Name.LocalName == "unit"))
        {
            if (unit.Attribute("id")?.Value is not { Length: > 0 } id)
            {
                continue;
            }

            var target = unit.Descendants().FirstOrDefault(e => e.Name.LocalName == "target")?.Value;
            var source = unit.Descendants().FirstOrDefault(e => e.Name.LocalName == "source")?.Value;
            if (!string.IsNullOrEmpty(target) || !string.IsNullOrEmpty(source))
            {
                result[id] = string.IsNullOrEmpty(target) ? source! : target;
            }
        }

        return result;
    }

    /// <summary>Catalog 類型名 → XLIFF 文件名（去掉 LangResourceKind 後綴）。</summary>
    private static string CatalogOf<TKind>()
        where TKind : struct, Enum =>
        typeof(TKind).Name.Replace("LangResourceKind", "", StringComparison.Ordinal);
}
