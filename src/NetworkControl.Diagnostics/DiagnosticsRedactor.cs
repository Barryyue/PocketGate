using System.Text.RegularExpressions;

namespace NetworkControl.Diagnostics;

/// <summary>
/// 診斷包文本脫敏：用戶目錄中的用戶名（<c>C:\Users\張三\...</c>、<c>/home/zhang/...</c>，包括 WFP 的小寫設備路徑）
/// 和計算機名替換為佔位符。程序路徑的其餘部分保留，否則無法分析規則匹配問題。
/// 界面和服務共用：兩邊各自收集的文本都要經過同一套規則。
/// </summary>
public sealed partial class DiagnosticsRedactor
{
    public const string UserPlaceholder = "<user>";

    public const string MachinePlaceholder = "<machine>";

    // 太短的名字（例如 "pc"）按整詞替換也容易誤傷正常文本，不做字面替換（路徑中的仍會被正則處理）
    private const int MinLiteralLength = 3;

    private readonly (Regex Pattern, string Replacement)[] _literals;

    public DiagnosticsRedactor(IEnumerable<string>? userNames = null, string? machineName = null)
    {
        var literals = new List<(Regex, string)>();
        if (machineName is { Length: >= MinLiteralLength })
        {
            literals.Add((WholeWord(machineName), MachinePlaceholder));
        }

        foreach (var name in userNames ?? [])
        {
            if (name is { Length: >= MinLiteralLength } && !IsWellKnownAccount(name))
            {
                literals.Add((WholeWord(name), UserPlaceholder));
            }
        }

        _literals = [.. literals];
    }

    /// <summary>按當前進程的用戶和計算機創建。以 SYSTEM 運行的服務只會替換計算機名和路徑中的用戶名。</summary>
    public static DiagnosticsRedactor ForCurrentProcess() => new([Environment.UserName], Environment.MachineName);

    public string Redact(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return text;
        }

        var result = UserProfilePath().Replace(text, UserPlaceholder);
        foreach (var (pattern, replacement) in _literals)
        {
            result = pattern.Replace(result, replacement);
        }

        return result;
    }

    private static bool IsWellKnownAccount(string name) =>
        name.Equals("SYSTEM", StringComparison.OrdinalIgnoreCase)
        || name.Equals("LOCAL SERVICE", StringComparison.OrdinalIgnoreCase)
        || name.Equals("NETWORK SERVICE", StringComparison.OrdinalIgnoreCase)
        || name.Equals("root", StringComparison.Ordinal)
        || name.EndsWith('$');

    private static Regex WholeWord(string value) =>
        new($@"(?<![\p{{L}}\p{{N}}_]){Regex.Escape(value)}(?![\p{{L}}\p{{N}}_])", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    /// <summary>
    /// Users / home 目錄下的第一級目錄名（公共和默認配置文件目錄除外）。
    /// 用戶名可以含空格：後面跟著路徑分隔符時整段匹配；否則（路徑在用戶目錄處結束）匹配到空白為止。
    /// </summary>
    [GeneratedRegex(
        @"(?<=[\\/](?:Users|home)[\\/])(?!(?:Public|Default|Default User|All Users)(?:[\\/]|$))(?:[^\\/:*?""<>|\r\n\t;,]+?(?=[\\/])|[^\\/:*?""<>|\s;,]+)",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex UserProfilePath();
}
