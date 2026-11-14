using Avalonia.Controls;

namespace NetworkControl.App.Views;

public sealed partial class OverviewView : UserControl
{
    public OverviewView()
    {
        InitializeComponent();
        SizeChanged += OnSizeChanged;
        ApplyResponsiveLayout(Bounds.Width);
    }

    private void OnSizeChanged(object? sender, SizeChangedEventArgs e) => ApplyResponsiveLayout(e.NewSize.Width);

    private void ApplyResponsiveLayout(double width)
    {
        var compact = width > 0 && width < 1000;
        KpiGrid.ColumnDefinitions = compact ? new ColumnDefinitions("*,*") : new ColumnDefinitions("*,*,*,*");
        KpiGrid.RowDefinitions = compact ? new RowDefinitions("Auto,Auto") : new RowDefinitions("Auto");
        for (var index = 0; index < KpiGrid.Children.Count; index++)
        {
            Grid.SetRow(KpiGrid.Children[index], compact ? index / 2 : 0);
            Grid.SetColumn(KpiGrid.Children[index], compact ? index % 2 : index);
        }

        TrafficGrid.ColumnDefinitions = compact ? new ColumnDefinitions("*") : new ColumnDefinitions("2*,*");
        TrafficGrid.RowDefinitions = compact ? new RowDefinitions("Auto,Auto") : new RowDefinitions("Auto");
        for (var index = 0; index < TrafficGrid.Children.Count; index++)
        {
            Grid.SetRow(TrafficGrid.Children[index], compact ? index : 0);
            Grid.SetColumn(TrafficGrid.Children[index], compact ? 0 : index);
        }

        RankingGrid.ColumnDefinitions = compact ? new ColumnDefinitions("*") : new ColumnDefinitions("*,*");
        RankingGrid.RowDefinitions = compact ? new RowDefinitions("Auto,Auto") : new RowDefinitions("Auto");
        for (var index = 0; index < RankingGrid.Children.Count; index++)
        {
            Grid.SetRow(RankingGrid.Children[index], compact ? index : 0);
            Grid.SetColumn(RankingGrid.Children[index], compact ? 0 : index);
        }
    }
}
