using Avalonia;
using Avalonia.Controls;

namespace NetworkControl.App.Views;

public sealed partial class ToastWindow : Window
{
    private const int ScreenMargin = 16;

    public ToastWindow() => InitializeComponent();

    /// <summary>放在屏幕右下角、距底部 <paramref name="offsetFromBottom"/> 像素的位置；返回本窗口佔用的高度（像素）。</summary>
    public double PlaceAboveBottom(double offsetFromBottom)
    {
        var screen = Screens.ScreenFromWindow(this) ?? Screens.Primary;
        if (screen is null)
        {
            return 0;
        }

        var scaling = screen.Scaling;
        var width = Math.Ceiling(Bounds.Width * scaling);
        var height = Math.Ceiling(Bounds.Height * scaling);
        var margin = ScreenMargin * scaling;
        var area = screen.WorkingArea;
        Position = new PixelPoint((int)(area.Right - width - margin), (int)(area.Bottom - height - margin - offsetFromBottom));
        return height + (8 * scaling);
    }
}
