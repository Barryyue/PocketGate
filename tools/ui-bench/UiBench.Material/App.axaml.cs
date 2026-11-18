using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using UiBench.Common;

namespace UiBench.Material;

public sealed partial class App : Avalonia.Application
{
    private BenchViewModel? _viewModel;

    public override void Initialize()
    {
        BenchMetrics.Mark("avalonia_init_start");
        AvaloniaXamlLoader.Load(this);
        BenchMetrics.Mark("avalonia_init_end");
    }

    public override void OnFrameworkInitializationCompleted()
    {
        _viewModel = new BenchViewModel("Material");
        switch (ApplicationLifetime)
        {
            case IActivityApplicationLifetime activity:
                activity.MainViewFactory = CreateMainView;
                break;
            case ISingleViewApplicationLifetime single:
                single.MainView = CreateMainView();
                break;
        }

        BenchMetrics.Mark("framework_ready");
        base.OnFrameworkInitializationCompleted();
    }

    private MainView CreateMainView() => new() { DataContext = _viewModel };
}
