using Avalonia.Media.Imaging;
using NetworkControl.App.Services;

namespace NetworkControl.App.ViewModels;

/// <summary>帶應用圖標的列表行：共用的圖標加載與變更通知。</summary>
public abstract class IconRowViewModel(AppIconCache icons) : ViewModelBase
{
    public Bitmap? Icon { get; private set; }

    public bool HasIcon => Icon is not null;

    protected async Task LoadIconAsync(string path)
    {
        if (path.Length == 0)
        {
            return;
        }

        if (await icons.GetAsync(path) is { } icon)
        {
            Icon = icon;
            OnPropertyChanged(nameof(Icon));
            OnPropertyChanged(nameof(HasIcon));
        }
    }
}
