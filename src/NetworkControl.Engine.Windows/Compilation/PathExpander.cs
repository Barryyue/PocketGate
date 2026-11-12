using NetworkControl.Core.Matching;

namespace NetworkControl.Engine.Windows.Compilation;

/// <summary>
/// 把帶通配符的路徑模式展開為磁盤上實際存在的文件（WFP 只能按具體的應用 ID 匹配）。
/// <c>*</c> 匹配單個路徑段，<c>**</c> 匹配任意層級（限制遍歷深度和數量，避免掃描整個磁盤）。
/// </summary>
internal static class PathExpander
{
    private const int MaxResults = 64;
    private const int MaxRecursiveDepth = 6;

    public static IReadOnlyList<string> Expand(string pattern)
    {
        if (!pattern.Contains('*', StringComparison.Ordinal))
        {
            return File.Exists(pattern) ? [pattern] : [];
        }

        var segments = pattern.Split(['\\', '/'], StringSplitOptions.None);
        var firstWildcard = Array.FindIndex(segments, s => s.Contains('*', StringComparison.Ordinal));
        var root = string.Join(Path.DirectorySeparatorChar, segments[..firstWildcard]);
        if (root.Length == 0 || !Directory.Exists(root + Path.DirectorySeparatorChar))
        {
            return [];
        }

        var matcher = PathPattern.Parse(pattern, ignoreCase: true);
        var recursive = pattern.Contains("**", StringComparison.Ordinal);
        var options = new EnumerationOptions
        {
            RecurseSubdirectories = true,
            MaxRecursionDepth = recursive ? MaxRecursiveDepth : segments.Length - firstWildcard,
            IgnoreInaccessible = true,
            AttributesToSkip = FileAttributes.ReparsePoint,
        };

        try
        {
            return Directory.EnumerateFiles(root + Path.DirectorySeparatorChar, segments[^1].Contains('*', StringComparison.Ordinal) ? "*" : segments[^1], options)
                .Where(matcher.Matches)
                .Take(MaxResults)
                .ToArray();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return [];
        }
    }
}
