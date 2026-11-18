using AtomUI;
using AtomUI.Desktop.Controls;
using AtomUI.Theme;
using AtomUI.Theme.Algorithms;
using AtomUI.Theme.Configuration;
using Avalonia;
using Avalonia.Android;
using Avalonia.Styling;
using global::Android.Runtime;
using UiBench.Common;

namespace UiBench.AtomUI;

[global::Android.App.Application(Label = "UI Bench AtomUI")]
public sealed class AndroidApp : AvaloniaAndroidApplication<App>
{
    public AndroidApp(IntPtr javaReference, JniHandleOwnership transfer)
        : base(javaReference, transfer)
    {
    }

    public override void OnCreate()
    {
        BenchMetrics.Variant = "AtomUI";
        BenchMetrics.Initialize();
        BenchMetrics.Mark("android_app_oncreate_start");
        base.OnCreate();
        BenchMetrics.Mark("android_app_oncreate_end");
    }

    protected override AppBuilder CustomizeAppBuilder(AppBuilder builder)
    {
        BenchMetrics.Mark("builder_start");
        var result = base.CustomizeAppBuilder(builder)
            .WithAtomUIDefaultOptions()
            .UseAtomUI(atom =>
            {
                atom.WithInitialTheme(IThemeManager.DEFAULT_THEME_ID, new ThemeConfigBuilder().WithAlgorithms(ThemeAlgorithm.Default).Build());
                atom.WithDefaultFontFamily("sans-serif");
                atom.UseDesktopControls();
            });
        BenchMetrics.Mark("builder_end");
        return result;
    }
}
