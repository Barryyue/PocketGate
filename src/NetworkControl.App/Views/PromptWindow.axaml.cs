using Avalonia;
using Avalonia.Input;
using NetworkControl.App.Services;
using NetworkControl.App.ViewModels;

namespace NetworkControl.App.Views;

/// <summary>詢問彈窗窗口。默認放在主屏工作區右下角（靠近托盤），置頂但不搶奪鍵盤焦點。</summary>
public sealed partial class PromptWindow : AtomUI.Desktop.Controls.Window
{
    private const int ScreenMargin = 16;

    public PromptWindow()
    {
        InitializeComponent();
        Icon = TrayIconRenderer.Render(TrayState.Ask);
        AppIcon.Source = TrayIconRenderer.RenderBitmap(TrayState.Ask);

        // 鼠標停留在彈窗上時暫停倒計時（服務端延長截止時間，有上限）
        PointerEntered += (_, _) => (DataContext as PromptViewModel)?.RequestPause();
    }

    protected override void OnOpened(EventArgs e)
    {
        base.OnOpened(e);
        PlaceNearTray();
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        // Esc：收起彈窗，按超時默認動作處理（由服務端計時）
        if (e.Key == Key.Escape)
        {
            Close();
            e.Handled = true;
            return;
        }

        base.OnKeyDown(e);
    }

    private void PlaceNearTray()
    {
        var screen = Screens.ScreenFromWindow(this) ?? Screens.Primary;
        if (screen is null)
        {
            return;
        }

        var area = screen.WorkingArea;
        var scaling = screen.Scaling;
        var width = (int)Math.Ceiling(Bounds.Width * scaling);
        var height = (int)Math.Ceiling(Bounds.Height * scaling);
        var margin = (int)(ScreenMargin * scaling);
        Position = new PixelPoint(area.Right - width - margin, area.Bottom - height - margin);
    }
}
