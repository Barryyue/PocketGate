using System.Diagnostics.CodeAnalysis;
using System.Globalization;

namespace NetworkControl.Engine.Vpn.Platform;

/// <summary>
/// Android 應用在 Core 中的「路徑」：Core 以可執行文件路徑作為應用主鍵，Android 用合成路徑表示一個軟件包
/// （docs/android.md §5.3）。規則中的路徑模式同樣使用這個格式，例如 <c>android-app://com.android.*</c>。
/// </summary>
public static class PackageAppPath
{
    public const string PackageScheme = "android-app://";

    /// <summary>查不到包名的 UID（應用已卸載等）。</summary>
    public const string UidScheme = "android-uid://";

    public static string ForPackage(string packageName)
    {
        ArgumentException.ThrowIfNullOrEmpty(packageName);
        return PackageScheme + packageName;
    }

    public static string ForUid(int uid) => UidScheme + uid.ToString(CultureInfo.InvariantCulture);

    public static bool TryGetPackage(string? path, [NotNullWhen(true)] out string? packageName)
    {
        if (path is not null && path.StartsWith(PackageScheme, StringComparison.Ordinal) && path.Length > PackageScheme.Length)
        {
            packageName = path[PackageScheme.Length..];
            return true;
        }

        packageName = null;
        return false;
    }

    public static bool TryGetUid(string? path, out int uid)
    {
        uid = 0;
        return path is not null
            && path.StartsWith(UidScheme, StringComparison.Ordinal)
            && int.TryParse(path.AsSpan(UidScheme.Length), NumberStyles.None, CultureInfo.InvariantCulture, out uid);
    }
}
