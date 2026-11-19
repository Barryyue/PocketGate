using Avalonia.Controls;
using Avalonia.Interactivity;
using NetworkControl.Android.ViewModels;

namespace NetworkControl.Android.Views;

/// <summary>
/// 主視圖。監聽子頁 ScrollViewer 滾動事件以驅動底部懸浮導覽列下滑隱藏 / 上滑彈回。
/// </summary>
public sealed partial class ShellView : UserControl
{
    public ShellView()
    {
        InitializeComponent();
        AddHandler(ScrollViewer.ScrollChangedEvent, OnAnyScrollChanged, RoutingStrategies.Bubble);
    }

    private void OnAnyScrollChanged(object? sender, ScrollChangedEventArgs e)
    {
        if (DataContext is MainViewModel vm && Math.Abs(e.OffsetDelta.Y) > 0.1)
        {
            vm.ReportScrollDelta(e.OffsetDelta.Y);
        }
    }
}
