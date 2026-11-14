using global::Android.App;
using global::Android.Content;
using global::Android.OS;
using global::Android.Views;

namespace UiBench.Common;

/// <summary>
/// 啟動階段的取樣點與資源指標。所有時間都是「距離進程啟動的毫秒數」，
/// 由 <see cref="Initialize"/> 在 <c>Application.OnCreate</c> 最前面調用。
/// </summary>
public static class BenchMetrics
{
    private static readonly List<(string Name, double Ms)> Marks = [];
    private static DateTime _processStartUtc;
    private static Context? _context;
    private static bool _firstFrameReported;

    public static string Variant { get; set; } = "?";

    public static event EventHandler? FirstFrame;

    public static IReadOnlyList<(string Name, double Ms)> Milestones => Marks;

    public static double ColdStartMs { get; private set; }

    public static double ApkMb { get; private set; }

    public static double PssMb { get; private set; }

    public static double ManagedMb { get; private set; }

    public static double NativeMb { get; private set; }

    public static int Threads { get; private set; }

    public static void Initialize()
    {
        try
        {
            _processStartUtc = System.Diagnostics.Process.GetCurrentProcess().StartTime.ToUniversalTime();
        }
        catch (Exception)
        {
            // 某些 Android 版本不提供進程啟動時間：退回到現在，啟動時間只計算我們能看到的階段
            _processStartUtc = DateTime.UtcNow;
        }

        Mark("process_start");
    }

    public static void Mark(string name)
    {
        if (_processStartUtc == default)
        {
            return;
        }

        var ms = (DateTime.UtcNow - _processStartUtc).TotalMilliseconds;
        Marks.Add((name, ms));
        global::Android.Util.Log.Info("UiBench", $"mark variant={Variant} name={name} ms={ms:F1}");
    }

    public static double? Get(string name)
    {
        foreach (var (markName, ms) in Marks)
        {
            if (markName == name)
            {
                return ms;
            }
        }

        return null;
    }

    public static void StartFirstFrameWatch(Activity activity)
    {
        _context = activity.ApplicationContext;
        var decor = activity.Window?.DecorView;
        if (decor is null)
        {
            return;
        }

        decor.Post(() => Choreographer.Instance!.PostFrameCallback(new FirstFrameCallback()));
    }

    public static void RefreshMemory(Context context)
    {
        _context ??= context;
        try
        {
            var info = context.PackageManager?.GetPackageInfo(context.PackageName!, 0);
            if (info?.ApplicationInfo?.SourceDir is { } path && File.Exists(path))
            {
                ApkMb = new FileInfo(path).Length / 1048576.0;
            }
        }
        catch (Exception)
        {
            // 取不到就顯示 0，不影響其他指標
        }

        var memory = new global::Android.OS.Debug.MemoryInfo();
        global::Android.OS.Debug.GetMemoryInfo(memory);
        PssMb = memory.TotalPss / 1024.0;
        NativeMb = memory.NativePss / 1024.0;
        ManagedMb = GC.GetTotalMemory(false) / 1048576.0;
        Threads = Java.Lang.Thread.ActiveCount();
    }

    /// <summary>首幀後輸出單行摘要，bench.ps1 會從 logcat 讀取。</summary>
    public static void LogSummary()
    {
        global::Android.Util.Log.Info(
            "UiBench",
            $"metrics variant={Variant} cold_start_ms={ColdStartMs:F1} apk_mb={ApkMb:F2} pss_mb={PssMb:F1} native_mb={NativeMb:F1} managed_mb={ManagedMb:F1} threads={Threads}");
    }

    private sealed class FirstFrameCallback : Java.Lang.Object, Choreographer.IFrameCallback
    {
        public void DoFrame(long frameTimeNanos)
        {
            if (_firstFrameReported)
            {
                return;
            }

            _firstFrameReported = true;
            ColdStartMs = (DateTime.UtcNow - _processStartUtc).TotalMilliseconds;
            Mark("first_frame");
            if (_context is { } context)
            {
                RefreshMemory(context);
            }

            LogSummary();
            FirstFrame?.Invoke(null, EventArgs.Empty);
        }
    }
}
