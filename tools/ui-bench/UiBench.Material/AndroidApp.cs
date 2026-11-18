using Avalonia;
using Avalonia.Android;
using global::Android.Runtime;
using UiBench.Common;

namespace UiBench.Material;

[global::Android.App.Application(Label = "UI Bench Material")]
public sealed class AndroidApp : AvaloniaAndroidApplication<App>
{
    public AndroidApp(IntPtr javaReference, JniHandleOwnership transfer)
        : base(javaReference, transfer)
    {
    }

    public override void OnCreate()
    {
        BenchMetrics.Variant = "Material";
        BenchMetrics.Initialize();
        BenchMetrics.Mark("android_app_oncreate_start");
        base.OnCreate();
        BenchMetrics.Mark("android_app_oncreate_end");
    }

    protected override AppBuilder CustomizeAppBuilder(AppBuilder builder)
    {
        // Material.Avalonia 的主題在 App.axaml 中由 MaterialTheme 提供，這裡沒有額外配置，
        // 只保留 builder_start/end 兩個取樣點讓三個變體可比。
        BenchMetrics.Mark("builder_start");
        var result = base.CustomizeAppBuilder(builder);
        BenchMetrics.Mark("builder_end");
        return result;
    }
}
