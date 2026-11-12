using Avalonia.Controls;
using Avalonia.Interactivity;
using NetworkControl.App.ViewModels;

namespace NetworkControl.App.Views;

public sealed partial class RulesView : UserControl
{
    public RulesView() => InitializeComponent();

    /// <summary>氣泡確認框只提供事件，在這裡轉發給視圖模型的刪除命令。</summary>
    private void OnDeleteConfirmed(object? sender, RoutedEventArgs e)
    {
        if (sender is Control { DataContext: RuleItemViewModel item } && DataContext is RulesViewModel vm)
        {
            vm.DeleteCommand.Execute(item);
        }
    }
}
