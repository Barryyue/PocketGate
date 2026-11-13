namespace NetworkControl.Storage.Serialization;

/// <summary>
/// 規則導出時把本機目錄替換為佔位符（如 <c>%USERPROFILE%</c>），導入時再展開，使規則可以跨機器使用（FR-SET-03）。
/// </summary>
/// <remarks>
/// 目錄表必須屬於<b>發起導出 / 導入的用戶</b>，而不是當前進程：服務以 SYSTEM 運行，
/// 它自己的 <see cref="Environment.GetFolderPath(Environment.SpecialFolder)"/> 指向 systemprofile。
/// 服務側由調用方（例如界面通過 IPC 傳來的用戶目錄）構造；<see cref="ForCurrentUser"/> 只適用於以用戶身份運行的進程。
/// </remarks>
public sealed class PathPlaceholderMapper
{
    public const string LocalAppData = "%LOCALAPPDATA%";
    public const string AppData = "%APPDATA%";
    public const string ProgramData = "%PROGRAMDATA%";
    public const string UserProfile = "%USERPROFILE%";

    // 按目錄長度從長到短匹配：%LOCALAPPDATA% 位於 %USERPROFILE% 之下，必須先匹配更具體的目錄
    private readonly (string Placeholder, string Root)[] _byRootLength;
    private readonly Dictionary<string, string> _byPlaceholder;
    private readonly StringComparison _comparison;

    /// <param name="roots">佔位符 → 本機目錄。目錄為空的項被忽略。</param>
    /// <param name="comparison">路徑比較方式；默認 Windows 不區分大小寫，其他平台區分。</param>
    public PathPlaceholderMapper(IEnumerable<KeyValuePair<string, string?>> roots, StringComparison? comparison = null)
    {
        ArgumentNullException.ThrowIfNull(roots);
        _comparison = comparison ?? (OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);

        var entries = roots
            .Where(entry => !string.IsNullOrWhiteSpace(entry.Value))
            .Select(entry => (Placeholder: entry.Key, Root: TrimSeparators(entry.Value!)))
            .Where(entry => entry.Root.Length > 0)
            .ToArray();

        _byRootLength = [.. entries.OrderByDescending(entry => entry.Root.Length)];
        _byPlaceholder = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var (placeholder, root) in entries)
        {
            _byPlaceholder[placeholder] = root;
        }
    }

    /// <summary>不做任何替換。</summary>
    public static PathPlaceholderMapper None { get; } = new([]);

    /// <summary>用當前進程用戶的目錄構造。服務（SYSTEM）中不要使用。</summary>
    public static PathPlaceholderMapper ForCurrentUser() => new(
    [
        new(LocalAppData, Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData)),
        new(AppData, Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData)),
        new(ProgramData, Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData)),
        new(UserProfile, Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)),
    ]);

    public string ToPlaceholder(string path)
    {
        ArgumentNullException.ThrowIfNull(path);
        foreach (var (placeholder, root) in _byRootLength)
        {
            if (StartsWithSegment(path, root, _comparison))
            {
                return placeholder + path[root.Length..];
            }
        }

        return path;
    }

    public string FromPlaceholder(string path)
    {
        ArgumentNullException.ThrowIfNull(path);
        if (!path.StartsWith('%'))
        {
            return path;
        }

        var end = path.IndexOf('%', 1);
        if (end < 0)
        {
            return path;
        }

        var placeholder = path[..(end + 1)];
        return _byPlaceholder.TryGetValue(placeholder, out var root) && IsBoundary(path, placeholder.Length)
            ? root + path[placeholder.Length..]
            : path;
    }

    /// <summary>前綴必須在路徑分隔符處結束：<c>C:\Users\bob</c> 不能匹配 <c>C:\Users\bobby</c>。</summary>
    private static bool StartsWithSegment(string path, string root, StringComparison comparison) =>
        path.StartsWith(root, comparison) && IsBoundary(path, root.Length);

    private static bool IsBoundary(string path, int index) =>
        index == path.Length || path[index] is '\\' or '/';

    private static string TrimSeparators(string root) => root.TrimEnd('\\', '/');
}
