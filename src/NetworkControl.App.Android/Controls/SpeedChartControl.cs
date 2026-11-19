using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;

namespace NetworkControl.Android.Controls;

/// <summary>
/// 36 點平滑貝茲即時網速波形圖控制項（與 Kotlin 版 SpeedChartCard 對齊）。
/// </summary>
public sealed class SpeedChartControl : Control
{
    public static readonly StyledProperty<IReadOnlyList<long>?> DownloadHistoryProperty =
        AvaloniaProperty.Register<SpeedChartControl, IReadOnlyList<long>?>(nameof(DownloadHistory));

    public static readonly StyledProperty<IReadOnlyList<long>?> UploadHistoryProperty =
        AvaloniaProperty.Register<SpeedChartControl, IReadOnlyList<long>?>(nameof(UploadHistory));

    public static readonly StyledProperty<Color> DownloadColorProperty =
        AvaloniaProperty.Register<SpeedChartControl, Color>(nameof(DownloadColor), Color.Parse("#FF2E7D32"));

    public static readonly StyledProperty<Color> UploadColorProperty =
        AvaloniaProperty.Register<SpeedChartControl, Color>(nameof(UploadColor), Color.Parse("#FF0284C7"));

    static SpeedChartControl()
    {
        AffectsRender<SpeedChartControl>(DownloadHistoryProperty, UploadHistoryProperty, DownloadColorProperty, UploadColorProperty);
    }

    public IReadOnlyList<long>? DownloadHistory
    {
        get => GetValue(DownloadHistoryProperty);
        set => SetValue(DownloadHistoryProperty, value);
    }

    public IReadOnlyList<long>? UploadHistory
    {
        get => GetValue(UploadHistoryProperty);
        set => SetValue(UploadHistoryProperty, value);
    }

    public Color DownloadColor
    {
        get => GetValue(DownloadColorProperty);
        set => SetValue(DownloadColorProperty, value);
    }

    public Color UploadColor
    {
        get => GetValue(UploadColorProperty);
        set => SetValue(UploadColorProperty, value);
    }

    public override void Render(DrawingContext context)
    {
        base.Render(context);
        var bounds = Bounds;
        if (bounds.Width <= 4 || bounds.Height <= 4) return;

        var down = DownloadHistory;
        var up = UploadHistory;
        var maxVal = 1024L;
        if (down is not null)
        {
            foreach (var v in down) if (v > maxVal) maxVal = v;
        }
        if (up is not null)
        {
            foreach (var v in up) if (v > maxVal) maxVal = v;
        }

        // 輕量水平參考線
        var gridPen = new Pen(new SolidColorBrush(Color.FromArgb(24, 128, 128, 128)), 1);
        for (var i = 1; i <= 2; i++)
        {
            var y = bounds.Height * i / 3.0;
            context.DrawLine(gridPen, new Point(0, y), new Point(bounds.Width, y));
        }

        if (up is { Count: >= 2 })
        {
            DrawCurve(context, bounds, up, maxVal, UploadColor, fillAlpha: 28, strokeWidth: 1.8);
        }
        if (down is { Count: >= 2 })
        {
            DrawCurve(context, bounds, down, maxVal, DownloadColor, fillAlpha: 56, strokeWidth: 2.4);
        }
    }

    private static void DrawCurve(
        DrawingContext context,
        Rect bounds,
        IReadOnlyList<long> samples,
        long maxVal,
        Color color,
        byte fillAlpha,
        double strokeWidth)
    {
        var count = samples.Count;
        var stepX = bounds.Width / (count - 1);
        var padY = 4.0;
        var usableH = Math.Max(4.0, bounds.Height - padY * 2);

        var pts = new Point[count];
        for (var i = 0; i < count; i++)
        {
            var ratio = Math.Clamp((double)samples[i] / maxVal, 0.0, 1.0);
            pts[i] = new Point(i * stepX, bounds.Height - padY - ratio * usableH);
        }

        var stroke = new StreamGeometry();
        using (var ctx = stroke.Open())
        {
            ctx.BeginFigure(pts[0], isFilled: false);
            for (var i = 1; i < count; i++)
            {
                var prev = pts[i - 1];
                var curr = pts[i];
                var midX = (prev.X + curr.X) * 0.5;
                ctx.CubicBezierTo(new Point(midX, prev.Y), new Point(midX, curr.Y), curr);
            }
            ctx.EndFigure(isClosed: false);
        }

        var fill = new StreamGeometry();
        using (var ctx = fill.Open())
        {
            ctx.BeginFigure(new Point(pts[0].X, bounds.Height), isFilled: true);
            ctx.LineTo(pts[0]);
            for (var i = 1; i < count; i++)
            {
                var prev = pts[i - 1];
                var curr = pts[i];
                var midX = (prev.X + curr.X) * 0.5;
                ctx.CubicBezierTo(new Point(midX, prev.Y), new Point(midX, curr.Y), curr);
            }
            ctx.LineTo(new Point(pts[^1].X, bounds.Height));
            ctx.EndFigure(isClosed: true);
        }

        var brush = new LinearGradientBrush
        {
            StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
            EndPoint = new RelativePoint(0, 1, RelativeUnit.Relative),
            GradientStops =
            [
                new GradientStop(Color.FromArgb(fillAlpha, color.R, color.G, color.B), 0),
                new GradientStop(Color.FromArgb(0, color.R, color.G, color.B), 1),
            ],
        };
        context.DrawGeometry(brush, null, fill);
        context.DrawGeometry(null, new Pen(new SolidColorBrush(color), strokeWidth, lineCap: PenLineCap.Round), stroke);
    }
}
