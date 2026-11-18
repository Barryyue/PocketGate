using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace UiBench.Common;

/// <summary>里程碑列表中的一行。</summary>
public sealed record MilestoneRow(string Name, string Value);

/// <summary>效能面板的視圖模型；三個 UI 變體共用。</summary>
public sealed class BenchViewModel(string variant) : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    public string Variant { get; } = variant;

    public ObservableCollection<MilestoneRow> Milestones { get; } = [];

    public string ColdStart => Ms(BenchMetrics.Get("first_frame"));

    public string RuntimeInit => Ms(BenchMetrics.Get("android_app_oncreate_start"));

    public string UiLibrarySetup => Ms(Diff("builder_start", "builder_end"));

    public string AvaloniaInit => Ms(Diff("avalonia_init_start", "avalonia_init_end"));

    public string ActivityCreate => Ms(Diff("activity_create_start", "activity_create_end"));

    public string FirstFrame => Ms(Diff("activity_create_end", "first_frame"));

    public string ApkSize => BenchMetrics.ApkMb > 0 ? $"{BenchMetrics.ApkMb:F2} MB" : "—";

    public string Pss => BenchMetrics.PssMb > 0 ? $"{BenchMetrics.PssMb:F1} MB" : "—";

    public string Managed => BenchMetrics.ManagedMb > 0 ? $"{BenchMetrics.ManagedMb:F1} MB" : "—";

    public string Native => BenchMetrics.NativeMb > 0 ? $"{BenchMetrics.NativeMb:F1} MB" : "—";

    public string Threads => BenchMetrics.Threads > 0 ? BenchMetrics.Threads.ToString() : "—";

    public string Fps => _frames is { Fps: > 0 } f ? $"{f.Fps:F0} FPS" : "—";

    public string WorstFrame => _frames is { WorstMs: > 0 } f ? $"{f.WorstMs:F1} ms" : "—";

    public string JankFrames => _frames is { } f ? f.JankFrames.ToString() : "—";

    public string LayoutResult { get; private set; } = "尚未量測";

    public string ScrollResult { get; private set; } = "尚未量測";

    public string Status => BenchMetrics.ColdStartMs > 0 ? "首幀完成" : "量測中…";

    private FrameStats? _frames;

    public void Refresh()
    {
        Milestones.Clear();
        foreach (var (name, ms) in BenchMetrics.Milestones)
        {
            Milestones.Add(new MilestoneRow(Label(name), $"{ms:F0} ms"));
        }

        OnPropertyChanged(string.Empty);
    }

    public void AttachFrames(FrameStats frames)
    {
        _frames = frames;
        frames.Updated += (_, _) => UpdateFrames();
    }

    public void UpdateFrames()
    {
        OnPropertyChanged(nameof(Fps));
        OnPropertyChanged(nameof(WorstFrame));
        OnPropertyChanged(nameof(JankFrames));
    }

    public void SetLayoutResult(double ms)
    {
        LayoutResult = $"{ms:F1} ms（300 行）";
        OnPropertyChanged(nameof(LayoutResult));
    }

    public void SetScrollResult(FrameSnapshot snapshot)
    {
        ScrollResult = $"{snapshot.Fps:F0} FPS，最差 {snapshot.WorstMs:F1} ms，掉幀 {snapshot.JankFrames}";
        OnPropertyChanged(nameof(ScrollResult));
    }

    private static double? Diff(string from, string to)
    {
        var start = BenchMetrics.Get(from);
        var end = BenchMetrics.Get(to);
        return start is { } s && end is { } e ? e - s : null;
    }

    private static string Ms(double? value) => value is { } ms ? $"{ms:F0} ms" : "—";

    private static string Label(string name) => name switch
    {
        "process_start" => "進程啟動",
        "android_app_oncreate_start" => "Application.OnCreate",
        "android_app_oncreate_end" => "Application 初始化完成",
        "builder_start" => "UI 庫配置開始",
        "builder_end" => "UI 庫配置完成",
        "avalonia_init_start" => "Avalonia Initialize",
        "avalonia_init_end" => "Avalonia 初始化完成",
        "framework_ready" => "框架就緒",
        "activity_create_start" => "Activity.OnCreate",
        "activity_create_end" => "Activity 建立完成",
        "first_frame" => "首幀",
        _ => name,
    };

    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
