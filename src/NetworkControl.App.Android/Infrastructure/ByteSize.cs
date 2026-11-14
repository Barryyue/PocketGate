using System.Globalization;

namespace NetworkControl.Android.Infrastructure;

/// <summary>字節數的緊湊顯示（1024 進制）：512 B、1.5 KB、23 MB、1.2 GB。與桌面版的格式一致。</summary>
public static class ByteSize
{
    private static readonly string[] Units = ["B", "KB", "MB", "GB", "TB"];

    public static string Format(long value)
    {
        double size = value;
        var unit = 0;
        while (size >= 1024 && unit < Units.Length - 1)
        {
            size /= 1024;
            unit++;
        }

        var format = unit == 0 || size >= 100 ? "0" : size >= 10 ? "0.#" : "0.##";
        return size.ToString(format, CultureInfo.InvariantCulture) + " " + Units[unit];
    }
}
