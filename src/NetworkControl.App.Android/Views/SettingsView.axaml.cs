using Avalonia.Controls;
using Avalonia.Interactivity;
using NetworkControl.Android.ViewModels;

namespace NetworkControl.Android.Views;

public sealed partial class SettingsView : UserControl
{
    public SettingsView()
    {
        InitializeComponent();
    }

    private void OnDeleteProfileConfirmed(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not SettingsViewModel vm)
        {
            return;
        }

        // 刪除按鈕在配置文件卡片上（不是每行一個），DataContext 可能是行或視圖模型
        if (sender is Control { DataContext: ProfileRow row })
        {
            vm.SelectedProfile = row;
        }

        if (vm.CanDeleteSelected)
        {
            vm.DeleteProfileCommand.Execute(null);
        }
    }
}
