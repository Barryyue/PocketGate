using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using NetworkControl.App.ViewModels;

namespace NetworkControl.App.Views;

public sealed partial class RuleEditorView : UserControl
{
    public RuleEditorView() => InitializeComponent();

    /// <summary>瀏覽可執行文件。文件選擇器屬於視圖職責，選中後回填到視圖模型。</summary>
    private async void OnBrowseClicked(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not RuleEditorViewModel vm || TopLevel.GetTopLevel(this)?.StorageProvider is not { } storage)
        {
            return;
        }

        var files = await storage.OpenFilePickerAsync(new FilePickerOpenOptions { AllowMultiple = false });
        if (files.Count > 0 && files[0].TryGetLocalPath() is { } path)
        {
            vm.AppPath = path;
        }
    }
}
