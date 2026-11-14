using Avalonia.Controls;
using Avalonia.Interactivity;
using NetworkControl.App.ViewModels;

namespace NetworkControl.App.Views;

public sealed partial class ConnectionsView : UserControl
{
    public ConnectionsView() => InitializeComponent();

    /// <summary>確認氣泡只提供事件，這裡轉發給視圖模型的斷開命令。</summary>
    private void OnKillConfirmed(object? sender, RoutedEventArgs e)
    {
        if (sender is Control { DataContext: ConnectionItemViewModel item } && DataContext is ConnectionsViewModel vm)
        {
            vm.KillCommand.Execute(item);
        }
    }
}
