using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Layout;
using UiBench.Common;

namespace UiBench.Fluent;

public sealed partial class MainView : UserControl
{
    private readonly FrameStats _frames = new();
    private bool _started;

    public MainView()
    {
        InitializeComponent();
        for (var i = 0; i < 40; i++)
        {
            ScrollList.Children.Add(new TextBlock
            {
                Text = $"應用程式 {i:00} — com.example.application{i:00}",
                Height = 28,
                VerticalAlignment = VerticalAlignment.Center,
            });
        }

        Loaded += OnLoaded;
        Unloaded += (_, _) => _frames.Stop();
    }

    private void OnLoaded(object? sender, RoutedEventArgs e)
    {
        if (_started || DataContext is not BenchViewModel viewModel)
        {
            return;
        }

        _started = true;
        viewModel.AttachFrames(_frames);
        BenchMetrics.FirstFrame += (_, _) => viewModel.Refresh();
        viewModel.Refresh();
        _frames.Start();
    }

    private void OnLayoutTest(object? sender, RoutedEventArgs e)
    {
        if (DataContext is BenchViewModel viewModel)
        {
            viewModel.SetLayoutResult(BenchUi.RunLayoutTest(LayoutHost));
        }
    }

    private async void OnScrollTest(object? sender, RoutedEventArgs e)
    {
        if (DataContext is BenchViewModel viewModel)
        {
            var snapshot = await BenchUi.RunScrollTestAsync(ScrollHost, _frames, TimeSpan.FromSeconds(3));
            viewModel.SetScrollResult(snapshot);
        }
    }
}
