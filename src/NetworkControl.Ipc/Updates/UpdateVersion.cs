using System.Globalization;

namespace NetworkControl.Ipc.Updates;

/// <summary>
/// 用於比較的版本號：忽略 <c>+提交哈希</c> 後綴（.NET 的 InformationalVersion 會帶上它），
/// 缺少的段補 0，因此 <c>0.4</c> 與 <c>0.4.0</c> 相等。
/// 預發布標籤（例如 <c>-beta</c>）不支持：清單填錯時視為無效，避免誤提示升級。
/// </summary>
public readonly record struct UpdateVersion(int Major, int Minor, int Build, int Revision) : IComparable<UpdateVersion>
{
    public static bool TryParse(string? text, out UpdateVersion version)
    {
        version = default;
        if (string.IsNullOrWhiteSpace(text))
        {
            return false;
        }

        var value = text.Trim();
        var plus = value.IndexOf('+', StringComparison.Ordinal);
        if (plus >= 0)
        {
            value = value[..plus];
        }

        if (!Version.TryParse(value, out var parsed) || parsed.Major < 0 || parsed.Minor < 0)
        {
            return false;
        }

        // Version 把缺少的段報告為 -1；比較前統一補 0，讓 0.4 與 0.4.0 相等
        version = new UpdateVersion(parsed.Major, parsed.Minor, Math.Max(parsed.Build, 0), Math.Max(parsed.Revision, 0));
        return true;
    }

    public int CompareTo(UpdateVersion other)
    {
        var result = Major.CompareTo(other.Major);
        if (result != 0)
        {
            return result;
        }

        result = Minor.CompareTo(other.Minor);
        if (result != 0)
        {
            return result;
        }

        result = Build.CompareTo(other.Build);
        return result != 0 ? result : Revision.CompareTo(other.Revision);
    }

    public static bool operator <(UpdateVersion left, UpdateVersion right) => left.CompareTo(right) < 0;

    public static bool operator <=(UpdateVersion left, UpdateVersion right) => left.CompareTo(right) <= 0;

    public static bool operator >(UpdateVersion left, UpdateVersion right) => left.CompareTo(right) > 0;

    public static bool operator >=(UpdateVersion left, UpdateVersion right) => left.CompareTo(right) >= 0;

    public override string ToString() =>
        Revision > 0
            ? string.Create(CultureInfo.InvariantCulture, $"{Major}.{Minor}.{Build}.{Revision}")
            : string.Create(CultureInfo.InvariantCulture, $"{Major}.{Minor}.{Build}");
}
