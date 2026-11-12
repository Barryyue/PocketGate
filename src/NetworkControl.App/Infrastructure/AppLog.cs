using System.Globalization;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using Avalonia.Logging;

namespace NetworkControl.App.Infrastructure;

/// <summary>
/// 界面進程的日誌文件：%LocalAppData%\NetworkControl\logs\app-yyyyMMdd.log，按天一個文件，保留 7 天。
/// 記錄啟動信息、未處理異常、與服務的連接變化，以及 Avalonia 的警告（例如綁定失敗），供診斷包收集。
/// 刻意保持簡單、不引入日誌框架：寫入失敗時靜默忽略，任何情況下都不能影響界面本身。
/// </summary>
public static class AppLog
{
    public const string FilePrefix = "app-";

    private const int RetainedDays = 7;

    private const long MaxFileBytes = 10L * 1024 * 1024;

    private static readonly Lock Gate = new();

    public static string Directory { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NetworkControl", "logs");

    public static string Version { get; } =
        typeof(AppLog).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "0.0.0";

    /// <summary>程序入口調用一次：清理過期文件、掛接未處理異常、記錄啟動信息。</summary>
    public static void Initialize(string[] args)
    {
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            Error($"Unhandled exception (terminating: {e.IsTerminating})", e.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, e) => Warn("Unobserved task exception", e.Exception);

        Cleanup();
        Info(string.Create(CultureInfo.InvariantCulture,
            $"Starting NetworkControl UI {Version} (pid {Environment.ProcessId}, AOT {!RuntimeFeature.IsDynamicCodeSupported}, " +
            $"{RuntimeInformation.OSDescription}, {RuntimeInformation.ProcessArchitecture}, args: [{string.Join(' ', args)}])"));
    }

    public static void Info(string message) => Write("INF", message, null);

    public static void Warn(string message, Exception? exception = null) => Write("WRN", message, exception);

    public static void Error(string message, Exception? exception = null) => Write("ERR", message, exception);

    public static IEnumerable<FileInfo> Files()
    {
        var directory = new DirectoryInfo(Directory);
        return directory.Exists
            ? directory.GetFiles(FilePrefix + "*.log").OrderByDescending(f => f.LastWriteTimeUtc)
            : [];
    }

    private static void Write(string level, string message, Exception? exception)
    {
        var now = DateTimeOffset.Now;
        var line = new StringBuilder()
            .Append(now.ToString("yyyy-MM-dd HH:mm:ss.fff zzz", CultureInfo.InvariantCulture))
            .Append(" [").Append(level).Append("] ")
            .Append(message);
        if (exception is not null)
        {
            line.AppendLine().Append(exception);
        }

        line.AppendLine();
        try
        {
            lock (Gate)
            {
                System.IO.Directory.CreateDirectory(Directory);
                var path = Path.Combine(Directory, $"{FilePrefix}{now:yyyyMMdd}.log");
                var info = new FileInfo(path);
                if (info.Exists && info.Length > MaxFileBytes)
                {
                    // 異常情況下（例如綁定錯誤刷屏）限制單個文件大小；當天後續日誌丟棄
                    return;
                }

                File.AppendAllText(path, line.ToString(), Encoding.UTF8);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // 日誌不可寫時不影響界面
        }
    }

    private static void Cleanup()
    {
        try
        {
            foreach (var file in Files().Where(f => f.LastWriteTimeUtc < DateTime.UtcNow.AddDays(-RetainedDays)))
            {
                file.Delete();
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // 下次啟動再清理
        }
    }
}

/// <summary>把 Avalonia 框架日誌（警告及以上，例如綁定失敗、佈局循環）寫入 <see cref="AppLog"/>。</summary>
public sealed class AppLogSink(LogEventLevel minimumLevel) : ILogSink
{
    public bool IsEnabled(LogEventLevel level, string area) => level >= minimumLevel;

    public void Log(LogEventLevel level, string area, object? source, string messageTemplate) =>
        Log(level, area, source, messageTemplate, []);

    public void Log(LogEventLevel level, string area, object? source, string messageTemplate, params object?[] propertyValues)
    {
        if (!IsEnabled(level, area))
        {
            return;
        }

        var message = $"Avalonia [{area}] {Format(messageTemplate, propertyValues)} (source: {source?.GetType().Name ?? "-"})";
        if (level >= LogEventLevel.Error)
        {
            AppLog.Error(message);
        }
        else
        {
            AppLog.Warn(message);
        }
    }

    /// <summary>按順序把 {Name} 佔位符替換為參數值（Avalonia 使用 Serilog 風格的消息模板）。</summary>
    internal static string Format(string template, object?[] values)
    {
        var result = new StringBuilder();
        var index = 0;
        for (var i = 0; i < template.Length; i++)
        {
            if (template[i] == '{' && template.IndexOf('}', i) is var end and > 0)
            {
                result.Append(index < values.Length ? Convert.ToString(values[index], CultureInfo.InvariantCulture) : template[i..(end + 1)]);
                index++;
                i = end;
            }
            else
            {
                result.Append(template[i]);
            }
        }

        return result.ToString();
    }
}
