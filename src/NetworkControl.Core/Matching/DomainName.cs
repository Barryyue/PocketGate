using System.Globalization;

namespace NetworkControl.Core.Matching;

/// <summary>域名規範化與模式匹配。</summary>
public static class DomainName
{
    private static readonly IdnMapping Idn = new();

    /// <summary>規範化：去掉首尾空白和結尾的點，轉為小寫的 ASCII（Punycode）形式。</summary>
    public static string Normalize(string name)
    {
        var trimmed = name.Trim().TrimEnd('.');
        if (trimmed.Length == 0)
        {
            return trimmed;
        }

        try
        {
            return Idn.GetAscii(trimmed).ToLowerInvariant();
        }
        catch (ArgumentException)
        {
            // 非法的 IDN 輸入：退化為小寫原文，避免因為一條髒數據影響整個判決流程
            return trimmed.ToLowerInvariant();
        }
    }
}

/// <summary>
/// 編譯後的域名模式。<c>*.example.com</c> 匹配 example.com 本身及其所有子域名。
/// </summary>
public sealed class DomainPattern
{
    private readonly string _suffix;

    private DomainPattern(string normalized, bool isWildcard)
    {
        Normalized = normalized;
        IsWildcard = isWildcard;
        _suffix = isWildcard ? normalized[2..] : normalized;
    }

    public string Normalized { get; }

    public bool IsWildcard { get; }

    public static DomainPattern Parse(string pattern)
    {
        var trimmed = pattern.Trim();
        if (trimmed.StartsWith("*.", StringComparison.Ordinal))
        {
            return new DomainPattern("*." + DomainName.Normalize(trimmed[2..]), isWildcard: true);
        }

        return new DomainPattern(DomainName.Normalize(trimmed), isWildcard: false);
    }

    /// <summary>判斷已規範化的域名是否匹配。</summary>
    public bool Matches(string normalizedDomain)
    {
        if (!IsWildcard)
        {
            return string.Equals(normalizedDomain, _suffix, StringComparison.Ordinal);
        }

        if (normalizedDomain.Length == _suffix.Length)
        {
            return string.Equals(normalizedDomain, _suffix, StringComparison.Ordinal);
        }

        return normalizedDomain.Length > _suffix.Length
            && normalizedDomain.EndsWith(_suffix, StringComparison.Ordinal)
            && normalizedDomain[normalizedDomain.Length - _suffix.Length - 1] == '.';
    }
}
