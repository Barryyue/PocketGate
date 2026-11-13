using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Input;
using Avalonia.Media;

namespace NetworkControl.App.Controls;

/// <summary>時間軸上的一根柱子。標籤和提示文本由視圖模型按當前語言生成。</summary>
public sealed record ChartPoint(long Allowed, long Blocked, string AxisLabel, string Tooltip)
{
    public long Total => Allowed + Blocked;
}

/// <summary>
/// 連接活動柱狀圖：每根柱子下半段為阻斷、上半段為允許（堆疊），懸停時高亮並顯示提示。
/// 顏色通過屬性傳入（XAML 中綁定 AtomUI 設計令牌），深淺色主題自動適配；不依賴第三方圖表庫。
/// </summary>
public sealed class ActivityChart : Control
{
    public static readonly StyledProperty<IReadOnlyList<ChartPoint>?> PointsProperty =
        AvaloniaProperty.Register<ActivityChart, IReadOnlyList<ChartPoint>?>(nameof(Points));

    public static readonly StyledProperty<IBrush?> AllowedBrushProperty =
        AvaloniaProperty.Register<ActivityChart, IBrush?>(nameof(AllowedBrush), Brushes.SteelBlue);

    public static readonly StyledProperty<IBrush?> BlockedBrushProperty =
        AvaloniaProperty.Register<ActivityChart, IBrush?>(nameof(BlockedBrush), Brushes.IndianRed);

    public static readonly StyledProperty<IBrush?> GridBrushProperty =
        AvaloniaProperty.Register<ActivityChart, IBrush?>(nameof(GridBrush), Brushes.LightGray);

    public static readonly StyledProperty<IBrush?> LabelBrushProperty =
        AvaloniaProperty.Register<ActivityChart, IBrush?>(nameof(LabelBrush), Brushes.Gray);

    public static readonly StyledProperty<IBrush?> HoverBrushProperty =
        AvaloniaProperty.Register<ActivityChart, IBrush?>(nameof(HoverBrush), new SolidColorBrush(Color.FromArgb(24, 128, 128, 128)));

    public static readonly StyledProperty<IBrush?> TooltipBackgroundProperty =
        AvaloniaProperty.Register<ActivityChart, IBrush?>(nameof(TooltipBackground), Brushes.White);

    public static readonly StyledProperty<IBrush?> TooltipForegroundProperty =
        AvaloniaProperty.Register<ActivityChart, IBrush?>(nameof(TooltipForeground), Brushes.Black);

    private const double AxisWidth = 44;
    private const double AxisHeight = 22;
    private const double LabelFontSize = 11;
    private const int GridLines = 4;

    private int _hovered = -1;

    static ActivityChart()
    {
        AffectsRender<ActivityChart>(
            PointsProperty,
            AllowedBrushProperty,
            BlockedBrushProperty,
            GridBrushProperty,
            LabelBrushProperty,
            HoverBrushProperty,
            TooltipBackgroundProperty,
            TooltipForegroundProperty);
    }

    public IReadOnlyList<ChartPoint>? Points
    {
        get => GetValue(PointsProperty);
        set => SetValue(PointsProperty, value);
    }

    public IBrush? AllowedBrush
    {
        get => GetValue(AllowedBrushProperty);
        set => SetValue(AllowedBrushProperty, value);
    }

    public IBrush? BlockedBrush
    {
        get => GetValue(BlockedBrushProperty);
        set => SetValue(BlockedBrushProperty, value);
    }

    public IBrush? GridBrush
    {
        get => GetValue(GridBrushProperty);
        set => SetValue(GridBrushProperty, value);
    }

    public IBrush? LabelBrush
    {
        get => GetValue(LabelBrushProperty);
        set => SetValue(LabelBrushProperty, value);
    }

    public IBrush? HoverBrush
    {
        get => GetValue(HoverBrushProperty);
        set => SetValue(HoverBrushProperty, value);
    }

    public IBrush? TooltipBackground
    {
        get => GetValue(TooltipBackgroundProperty);
        set => SetValue(TooltipBackgroundProperty, value);
    }

    public IBrush? TooltipForeground
    {
        get => GetValue(TooltipForegroundProperty);
        set => SetValue(TooltipForegroundProperty, value);
    }

    /// <summary>坐標軸的「好看」上限：1、2、5 × 10ⁿ 的倍數，保證網格線落在整數上。</summary>
    internal static long NiceMaximum(long max)
    {
        if (max <= GridLines)
        {
            return GridLines;
        }

        var magnitude = Math.Pow(10, Math.Floor(Math.Log10(max / (double)GridLines)));
        foreach (var step in new[] { 1d, 2d, 2.5d, 5d, 10d })
        {
            var candidate = (long)Math.Ceiling(step * magnitude) * GridLines;
            if (candidate >= max)
            {
                return candidate;
            }
        }

        return (long)Math.Ceiling(10 * magnitude) * GridLines;
    }

    /// <summary>軸標籤的緊湊格式：950、1.2k、3.4M。</summary>
    internal static string Compact(long value) => value switch
    {
        >= 1_000_000 => (value / 1_000_000d).ToString("0.#", CultureInfo.InvariantCulture) + "M",
        >= 10_000 => (value / 1_000d).ToString("0", CultureInfo.InvariantCulture) + "k",
        >= 1_000 => (value / 1_000d).ToString("0.#", CultureInfo.InvariantCulture) + "k",
        _ => value.ToString(CultureInfo.InvariantCulture),
    };

    protected override void OnPointerMoved(PointerEventArgs e)
    {
        base.OnPointerMoved(e);
        SetHovered(HitTest(e.GetPosition(this)));
    }

    protected override void OnPointerExited(PointerEventArgs e)
    {
        base.OnPointerExited(e);
        SetHovered(-1);
    }

    public override void Render(DrawingContext context)
    {
        var points = Points;
        var plot = PlotArea();
        if (plot.Width <= 0 || plot.Height <= 0)
        {
            return;
        }

        var max = NiceMaximum(points is { Count: > 0 } ? points.Max(p => p.Total) : 0);
        var gridPen = new Pen(GridBrush, 1, dashStyle: new DashStyle([4, 4], 0));
        var basePen = new Pen(GridBrush, 1);

        // 網格線和縱軸標籤
        for (var i = 0; i <= GridLines; i++)
        {
            var y = Math.Round(plot.Bottom - (plot.Height * i / GridLines)) + 0.5;
            context.DrawLine(i == 0 ? basePen : gridPen, new Point(plot.Left, y), new Point(plot.Right, y));
            var label = Text(Compact(max * i / GridLines), LabelBrush);
            context.DrawText(label, new Point(plot.Left - label.Width - 8, y - (label.Height / 2)));
        }

        if (points is not { Count: > 0 })
        {
            return;
        }

        var slot = plot.Width / points.Count;
        var barWidth = Math.Max(2, Math.Min(28, slot * 0.6));
        var labelEvery = Math.Max(1, (int)Math.Ceiling(points.Count / Math.Max(1, plot.Width / 64)));

        for (var i = 0; i < points.Count; i++)
        {
            var point = points[i];
            var center = plot.Left + (slot * (i + 0.5));
            if (i == _hovered)
            {
                context.FillRectangle(HoverBrush ?? Brushes.Transparent, new Rect(plot.Left + (slot * i), plot.Top, slot, plot.Height));
            }

            var blockedHeight = plot.Height * point.Blocked / max;
            var allowedHeight = plot.Height * point.Allowed / max;
            var x = center - (barWidth / 2);
            if (point.Blocked > 0)
            {
                context.DrawRectangle(BlockedBrush, null, new Rect(x, plot.Bottom - blockedHeight, barWidth, blockedHeight), 2, 2);
            }

            if (point.Allowed > 0)
            {
                context.DrawRectangle(AllowedBrush, null, new Rect(x, plot.Bottom - blockedHeight - allowedHeight, barWidth, allowedHeight), 2, 2);
            }

            if (i % labelEvery == 0 && point.AxisLabel.Length > 0)
            {
                var label = Text(point.AxisLabel, LabelBrush);
                context.DrawText(label, new Point(center - (label.Width / 2), plot.Bottom + 6));
            }
        }

        if (_hovered >= 0 && _hovered < points.Count)
        {
            DrawTooltip(context, plot, points[_hovered], plot.Left + (slot * (_hovered + 0.5)));
        }
    }

    private void DrawTooltip(DrawingContext context, Rect plot, ChartPoint point, double center)
    {
        var text = Text(point.Tooltip, TooltipForeground);
        var size = new Size(text.WidthIncludingTrailingWhitespace + 20, text.Height + 14);
        var left = Math.Clamp(center + 12, plot.Left, Math.Max(plot.Left, plot.Right - size.Width));
        if (center + 12 + size.Width > plot.Right)
        {
            left = Math.Max(plot.Left, center - 12 - size.Width);
        }

        var box = new Rect(new Point(left, plot.Top + 4), size);
        context.DrawRectangle(TooltipBackground, new Pen(GridBrush, 1), box, 6, 6);
        context.DrawText(text, new Point(box.Left + 10, box.Top + 7));
    }

    private Rect PlotArea() => new(AxisWidth, 8, Math.Max(0, Bounds.Width - AxisWidth - 8), Math.Max(0, Bounds.Height - AxisHeight - 8));

    private int HitTest(Point position)
    {
        var points = Points;
        var plot = PlotArea();
        if (points is not { Count: > 0 } || plot.Width <= 0 || position.X < plot.Left || position.X > plot.Right || position.Y < plot.Top || position.Y > plot.Bottom + AxisHeight)
        {
            return -1;
        }

        return Math.Clamp((int)((position.X - plot.Left) / (plot.Width / points.Count)), 0, points.Count - 1);
    }

    private void SetHovered(int index)
    {
        if (index != _hovered)
        {
            _hovered = index;
            InvalidateVisual();
        }
    }

    private FormattedText Text(string text, IBrush? brush) => new(
        text,
        CultureInfo.CurrentUICulture,
        FlowDirection.LeftToRight,
        new Typeface(TextElement.GetFontFamily(this)),
        LabelFontSize,
        brush ?? Brushes.Gray);
}

/// <summary>排行榜中的佔比條：按 <see cref="Maximum"/> 縮放，允許和阻斷兩段。</summary>
public sealed class ShareBar : Control
{
    public static readonly StyledProperty<double> AllowedProperty =
        AvaloniaProperty.Register<ShareBar, double>(nameof(Allowed));

    public static readonly StyledProperty<double> BlockedProperty =
        AvaloniaProperty.Register<ShareBar, double>(nameof(Blocked));

    public static readonly StyledProperty<double> MaximumProperty =
        AvaloniaProperty.Register<ShareBar, double>(nameof(Maximum), 1);

    public static readonly StyledProperty<IBrush?> AllowedBrushProperty =
        AvaloniaProperty.Register<ShareBar, IBrush?>(nameof(AllowedBrush), Brushes.SteelBlue);

    public static readonly StyledProperty<IBrush?> BlockedBrushProperty =
        AvaloniaProperty.Register<ShareBar, IBrush?>(nameof(BlockedBrush), Brushes.IndianRed);

    public static readonly StyledProperty<IBrush?> TrackBrushProperty =
        AvaloniaProperty.Register<ShareBar, IBrush?>(nameof(TrackBrush), Brushes.WhiteSmoke);

    static ShareBar()
    {
        AffectsRender<ShareBar>(AllowedProperty, BlockedProperty, MaximumProperty, AllowedBrushProperty, BlockedBrushProperty, TrackBrushProperty);
    }

    public double Allowed
    {
        get => GetValue(AllowedProperty);
        set => SetValue(AllowedProperty, value);
    }

    public double Blocked
    {
        get => GetValue(BlockedProperty);
        set => SetValue(BlockedProperty, value);
    }

    public double Maximum
    {
        get => GetValue(MaximumProperty);
        set => SetValue(MaximumProperty, value);
    }

    public IBrush? AllowedBrush
    {
        get => GetValue(AllowedBrushProperty);
        set => SetValue(AllowedBrushProperty, value);
    }

    public IBrush? BlockedBrush
    {
        get => GetValue(BlockedBrushProperty);
        set => SetValue(BlockedBrushProperty, value);
    }

    public IBrush? TrackBrush
    {
        get => GetValue(TrackBrushProperty);
        set => SetValue(TrackBrushProperty, value);
    }

    protected override Size MeasureOverride(Size availableSize) => new(0, 6);

    public override void Render(DrawingContext context)
    {
        var bounds = new Rect(Bounds.Size);
        var radius = bounds.Height / 2;
        context.DrawRectangle(TrackBrush, null, bounds, radius, radius);
        var max = Math.Max(1, Maximum);
        var allowedWidth = bounds.Width * Math.Clamp(Allowed / max, 0, 1);
        var blockedWidth = bounds.Width * Math.Clamp(Blocked / max, 0, 1 - (allowedWidth / Math.Max(1, bounds.Width)));
        if (allowedWidth > 0)
        {
            context.DrawRectangle(AllowedBrush, null, new Rect(0, 0, allowedWidth, bounds.Height), radius, radius);
        }

        if (blockedWidth > 0)
        {
            context.DrawRectangle(BlockedBrush, null, new Rect(allowedWidth, 0, blockedWidth, bounds.Height), radius, radius);
        }
    }
}
