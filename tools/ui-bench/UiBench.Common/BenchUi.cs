using System.Diagnostics;
using Avalonia;
using Avalonia.Controls;

namespace UiBench.Common;

/// <summary>UI 壓力測試：大量子控件的布局耗時，以及滾動時的幀率。</summary>
public static class BenchUi
{
    /// <summary>在 <paramref name="host"/> 中建立 <paramref name="count"/> 行，量測一次完整的 Measure/Arrange。</summary>
    public static double RunLayoutTest(Panel host, int count = 300)
    {
        host.Children.Clear();
        for (var i = 0; i < count; i++)
        {
            host.Children.Add(new TextBlock
            {
                Text = $"應用程式 {i:000} — com.example.application{i:000}",
                FontSize = 12,
                Height = 20,
            });
        }

        var stopwatch = Stopwatch.StartNew();
        host.Measure(new Size(1000, double.PositiveInfinity));
        host.Arrange(new Rect(0, 0, 1000, host.DesiredSize.Height));
        stopwatch.Stop();
        host.Children.Clear();
        host.InvalidateMeasure();
        return stopwatch.Elapsed.TotalMilliseconds;
    }

    /// <summary>在指定時間內把 ScrollViewer 從頂部滾到底部，回傳期間的幀統計。</summary>
    public static async Task<FrameSnapshot> RunScrollTestAsync(
        ScrollViewer scroller,
        FrameStats frames,
        TimeSpan duration)
    {
        frames.Reset();
        scroller.Offset = new Vector(0, 0);
        var stopwatch = Stopwatch.StartNew();
        while (stopwatch.Elapsed < duration)
        {
            var maximum = Math.Max(0, scroller.Extent.Height - scroller.Viewport.Height);
            var progress = Math.Clamp(stopwatch.Elapsed.TotalMilliseconds / duration.TotalMilliseconds, 0, 1);
            scroller.Offset = new Vector(0, maximum * progress);
            await Task.Delay(16);
        }

        var snapshot = frames.Snapshot();
        scroller.Offset = new Vector(0, 0);
        return snapshot;
    }
}
