using global::Android.Graphics;
using global::Android.Graphics.Drawables;

namespace NetworkControl.Android.Platform;

/// <summary>
/// 狀態欄原生實時網速小圖示（96×96 單色 Alpha 位圖，上行數字、下行單位，與 Kotlin 版 StatusBarSpeedIcon 對齊）。
/// </summary>
public static class StatusBarSpeedIcon
{
    public static Icon Render(long bytesPerSec)
    {
        var (num, unit) = SplitSpeed(bytesPerSec);
        var bmp = Bitmap.CreateBitmap(96, 96, Bitmap.Config.Alpha8!)!;
        using var canvas = new Canvas(bmp);
        using var numPaint = new Paint(PaintFlags.AntiAlias)
        {
            Color = Color.White,
            TextSize = num.Length >= 4 ? 38f : 44f,
            TextAlign = Paint.Align.Center,
            FakeBoldText = true,
        };
        using var unitPaint = new Paint(PaintFlags.AntiAlias)
        {
            Color = Color.White,
            TextSize = 24f,
            TextAlign = Paint.Align.Center,
            FakeBoldText = true,
        };

        canvas.DrawText(num, 48f, 46f, numPaint);
        canvas.DrawText(unit, 48f, 82f, unitPaint);
        return Icon.CreateWithBitmap(bmp);
    }

    public static (string Number, string Unit) SplitSpeed(long bytes)
    {
        if (bytes < 1024) return (bytes.ToString(System.Globalization.CultureInfo.InvariantCulture), "B/s");
        var kb = bytes / 1024.0;
        if (kb < 1024) return (kb < 100 ? $"{kb:0.#}" : $"{kb:0}", "K/s");
        var mb = kb / 1024.0;
        if (mb < 1024) return (mb < 100 ? $"{mb:0.#}" : $"{mb:0}", "M/s");
        var gb = mb / 1024.0;
        return ($"{gb:0.#}", "G/s");
    }
}
