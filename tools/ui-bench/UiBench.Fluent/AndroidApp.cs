using Avalonia;
using Avalonia.Android;
using global::Android.Runtime;
using UiBench.Common;

namespace UiBench.Fluent;

[global::Android.App.Application(Label = "UI Bench Fluent")]
public sealed class AndroidApp : AvaloniaAndroidApplication<App>
{
    public AndroidApp(IntPtr javaReference, JniHandleOwnership transfer)
        : base(javaReference, transfer)
    {
    }

    public override void OnCreate()
    {
        BenchMetrics.Variant = "Fluent";
        BenchMetrics.Initialize();
        BenchMetrics.Mark("android_app_oncreate_start");
        base.OnCreate();
        BenchMetrics.Mark("android_app_oncreate_end");
    }

    protected override AppBuilder CustomizeAppBuilder(AppBuilder builder)
    {
        BenchMetrics.Mark("builder_start");
        // 只有 Avalonia 自帶的 Fluent 主題，沒有額外的 UI 庫配置。
        var result = base.CustomizeAppBuilder(builder);
        BenchMetrics.Mark("builder_end");
        return result;
    }
}
