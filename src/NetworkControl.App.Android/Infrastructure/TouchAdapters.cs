using AtomUI.Desktop.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.VisualTree;

namespace NetworkControl.Android.Infrastructure;

/// <summary>
/// AtomUI 是桌面組件庫，部分控件只響應鼠標。這裡為觸控補上缺失的交互（見 AGENTS.md 第 6 節）：
/// <list type="bullet">
/// <item><see cref="Segmented"/>：觸控點擊選項不會選中（只處理鼠標按下）；改為在 Tapped 手勢上選中。</item>
/// </list>
/// 在應用初始化時調用一次 <see cref="Register"/>。
/// </summary>
public static class TouchAdapters
{
    private static bool _registered;

    public static void Register()
    {
        if (_registered)
        {
            return;
        }

        _registered = true;
        InputElement.TappedEvent.AddClassHandler<SegmentedItem>(OnSegmentedItemTapped, RoutingStrategies.Bubble, handledEventsToo: true);
    }

    private static void OnSegmentedItemTapped(SegmentedItem item, TappedEventArgs e)
    {
        if (e.Pointer.Type == PointerType.Mouse || !item.IsEffectivelyEnabled)
        {
            return;
        }

        if (item.FindAncestorOfType<Segmented>() is not { IsEffectivelyEnabled: true } segmented)
        {
            return;
        }

        var index = segmented.IndexFromContainer(item);
        if (index >= 0 && segmented.SelectedIndex != index)
        {
            segmented.SelectedIndex = index;
        }

        e.Handled = true;
    }
}
