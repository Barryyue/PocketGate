using Avalonia.Controls;
using Avalonia.Interactivity;
using NetworkControl.App.ViewModels;

namespace NetworkControl.App.Views;

public sealed partial class SettingsView : UserControl
{
    public SettingsView() => InitializeComponent();

    /// <summary>刪除配置文件的確認氣泡：轉發給視圖模型的刪除命令。</summary>
    private void OnDeleteProfileConfirmed(object? sender, RoutedEventArgs e)
    {
        if (sender is Control { DataContext: ProfileItemViewModel item } && DataContext is SettingsViewModel vm)
        {
            vm.DeleteProfileCommand.Execute(item);
        }
    }
}
