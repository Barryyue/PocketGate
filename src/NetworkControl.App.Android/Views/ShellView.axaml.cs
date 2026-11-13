using Avalonia;
using Avalonia.Controls;

namespace NetworkControl.Android.Views;

/// <summary>主視圖。Android 15+ 強制邊到邊顯示：按系統欄的安全區域留出內邊距。</summary>
public sealed partial class ShellView : UserControl
{
    public ShellView()
    {
        InitializeComponent();
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        if (TopLevel.GetTopLevel(this)?.InsetsManager is { } insets)
        {
            insets.DisplayEdgeToEdgePreference = true;
            ApplySafeArea(insets.SafeAreaPadding);
            insets.SafeAreaChanged += (_, args) => ApplySafeArea(args.SafeAreaPadding);
        }
    }

    private void ApplySafeArea(Thickness padding)
    {
        Root.Padding = new Thickness(padding.Left, padding.Top, padding.Right, 0);
        BottomNav.Padding = new Thickness(0, 0, 0, padding.Bottom);
    }
}
