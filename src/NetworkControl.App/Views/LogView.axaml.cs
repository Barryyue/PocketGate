using AtomUI.Desktop.Controls;
using Avalonia.Controls;
using NetworkControl.App.ViewModels;

namespace NetworkControl.App.Views;

public sealed partial class LogView : UserControl
{
    public LogView() => InitializeComponent();

    /// <summary>搜索框按回車或點擊搜索按鈕時查詢（服務端過濾，覆蓋歷史記錄）。</summary>
    private void OnSearchRequested(object? sender, SearchRequestedEventArgs e)
    {
        if (DataContext is LogViewModel vm)
        {
            vm.QueryCommand.Execute(null);
        }
    }
}
