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
        if (sender is Control { DataContext: ProfileRow row } && DataContext is SettingsViewModel vm)
        {
            vm.SelectedProfile = row;
            vm.DeleteProfileCommand.Execute(null);
        }
    }
}
