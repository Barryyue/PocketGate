using System.Globalization;
using NetworkControl.App.Infrastructure;

namespace NetworkControl.App.Services;

/// <summary>
/// 命令行導出診斷包，不啟動界面：<c>NetworkControl.exe --export-diagnostics [路徑] [--include-activity] [--no-configuration]</c>。
/// 用於界面本身無法啟動的情況；不受單實例限制，界面運行時也可以使用。路徑省略時保存到桌面。
/// </summary>
internal static class CommandLineExport
{
    public const string Switch = "--export-diagnostics";

    public static bool IsRequested(string[] args) => args.Contains(Switch, StringComparer.OrdinalIgnoreCase);

    public static int Run(string[] args)
    {
        var index = Array.FindIndex(args, a => a.Equals(Switch, StringComparison.OrdinalIgnoreCase));
        var path = index + 1 < args.Length && !args[index + 1].StartsWith("--", StringComparison.Ordinal)
            ? Path.GetFullPath(args[index + 1])
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), DiagnosticsExporter.SuggestedFileName(DateTime.Now));
        var options = new DiagnosticsExportOptions(
            IncludeConfiguration: !args.Contains("--no-configuration", StringComparer.OrdinalIgnoreCase),
            IncludeActivity: args.Contains("--include-activity", StringComparer.OrdinalIgnoreCase));

        try
        {
            var result = Task.Run(() => ExportAsync(path, options)).GetAwaiter().GetResult();
            AppLog.Info(string.Create(CultureInfo.InvariantCulture, $"Command-line diagnostics export written to {path}"));
            Console.WriteLine(path);
            if (!result.ServiceIncluded)
            {
                Console.WriteLine($"Service diagnostics not included: {result.ServiceError}");
            }

            return 0;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            AppLog.Error($"Command-line diagnostics export failed: {path}", ex);
            Console.Error.WriteLine(ex.Message);
            return 1;
        }
    }

    private static async Task<DiagnosticsExportResult> ExportAsync(string path, DiagnosticsExportOptions options)
    {
        var (context, channel) = await DiagnosticsContext.ConnectDirectAsync(CancellationToken.None);
        using (channel)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            await using var file = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None, 81920, useAsync: true);
            return await new DiagnosticsExporter(new ServiceLauncher(), new PreferencesStore()).ExportAsync(file, options, context, CancellationToken.None);
        }
    }
}
