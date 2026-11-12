using System.Text;
using System.Text.RegularExpressions;

namespace NetworkControl.Core.Matching;

/// <summary>
/// 可執行文件路徑模式：<c>*</c> 匹配單個路徑段內的任意字符，<c>**</c> 匹配任意層級。
/// Windows 和 macOS 默認不區分大小寫，Linux 區分大小寫。
/// </summary>
public sealed class PathPattern
{
    private static readonly TimeSpan MatchTimeout = TimeSpan.FromMilliseconds(50);

    private readonly Regex? _regex;
    private readonly string? _literal;
    private readonly StringComparison _comparison;

    private PathPattern(string pattern, bool ignoreCase)
    {
        Pattern = pattern;
        _comparison = ignoreCase ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;

        if (!pattern.Contains('*', StringComparison.Ordinal))
        {
            _literal = Normalize(pattern);
            return;
        }

        var options = RegexOptions.CultureInvariant | RegexOptions.NonBacktracking;
        if (ignoreCase)
        {
            options |= RegexOptions.IgnoreCase;
        }

        _regex = new Regex(ToRegex(Normalize(pattern)), options, MatchTimeout);
    }

    public string Pattern { get; }

    /// <summary>無通配符的模式可以用於精確索引。</summary>
    public string? Literal => _literal;

    public static bool DefaultIgnoreCase => !OperatingSystem.IsLinux();

    public static PathPattern Parse(string pattern, bool? ignoreCase = null) => new(pattern, ignoreCase ?? DefaultIgnoreCase);

    /// <summary>統一路徑分隔符，便於跨平台比較和建索引。</summary>
    public static string Normalize(string path) => path.Replace('\\', '/');

    public bool Matches(string path)
    {
        var normalized = Normalize(path);
        return _literal is not null
            ? string.Equals(normalized, _literal, _comparison)
            : _regex!.IsMatch(normalized);
    }

    private static string ToRegex(string pattern)
    {
        var sb = new StringBuilder("^");
        for (var i = 0; i < pattern.Length; i++)
        {
            var c = pattern[i];
            if (c == '*')
            {
                if (i + 1 < pattern.Length && pattern[i + 1] == '*')
                {
                    sb.Append(".*");
                    i++;
                }
                else
                {
                    sb.Append("[^/]*");
                }
            }
            else
            {
                sb.Append(Regex.Escape(c.ToString()));
            }
        }

        return sb.Append('$').ToString();
    }
}
