using Avalonia.Android;
using global::Android.App;
using global::Android.Content.PM;
using global::Android.OS;
using UiBench.Common;

namespace UiBench.AtomUI;

[Activity(
    Label = "UI Bench AtomUI",
    MainLauncher = true,
    Exported = true,
    Theme = "@style/Theme.AppCompat.DayNight.NoActionBar",
    ConfigurationChanges = ConfigChanges.Orientation | ConfigChanges.ScreenSize | ConfigChanges.UiMode | ConfigChanges.Locale | ConfigChanges.LayoutDirection)]
public sealed class MainActivity : AvaloniaMainActivity
{
    protected override void OnCreate(Bundle? savedInstanceState)
    {
        BenchMetrics.Mark("activity_create_start");
        base.OnCreate(savedInstanceState);
        BenchMetrics.Mark("activity_create_end");
        BenchMetrics.StartFirstFrameWatch(this);
    }
}
