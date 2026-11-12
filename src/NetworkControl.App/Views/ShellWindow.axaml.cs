using Avalonia.Controls;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Services;

namespace NetworkControl.App.Views;

public sealed partial class ShellWindow : AtomUI.Desktop.Controls.Window
{
    private readonly WindowHost? _host;

    public ShellWindow()
        : this(null)
    {
    }

    public ShellWindow(WindowHost? host)
    {
        _host = host;
        InitializeComponent();
        Icon = TrayIconRenderer.Render(TrayState.Ask);
        LogoImage.Source = TrayIconRenderer.RenderBitmap(TrayState.Ask);
    }

    protected override void OnOpened(EventArgs e)
    {
        base.OnOpened(e);
        _host?.Attach(this);
    }

    protected override void OnClosed(EventArgs e)
    {
        _host?.Detach(this);
        base.OnClosed(e);
    }
}
