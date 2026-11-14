using Avalonia.Controls;

namespace NetworkControl.Android.Views;

/// <summary>
/// 主視圖。Avalonia 12.1 在 Android 上默認把內容限制在系統欄之內，不要再按 SafeAreaPadding 手動留邊
/// （它以物理像素報告，疊加後頂部和底部會多出一大片空白，見 AGENTS.md 第 6 節）。
/// </summary>
public sealed partial class ShellView : UserControl
{
    public ShellView()
    {
        InitializeComponent();
    }
}
