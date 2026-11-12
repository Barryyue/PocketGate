using NetworkControl.Storage;
using Serilog;
using Serilog.Events;

namespace NetworkControl.Service.Diagnostics;

/// <summary>日誌配置（配置節點 NetworkControl:Logging）。</summary>
public sealed class LoggingOptions
{
    public const string SectionName = "NetworkControl:Logging";

    /// <summary>默認最低級別：Verbose / Debug / Information / Warning / Error / Fatal。</summary>
    public string MinimumLevel { get; set; } = nameof(LogEventLevel.Information);

    /// <summary>按日誌類別（命名空間前綴）覆蓋最低級別。</summary>
    public Dictionary<string, string> Overrides { get; set; } = new(StringComparer.OrdinalIgnoreCase)
    {
        ["Microsoft.AspNetCore"] = nameof(LogEventLevel.Warning),
        ["Grpc"] = nameof(LogEventLevel.Warning),
        ["Microsoft.AspNetCore.DataProtection"] = nameof(LogEventLevel.Error),
    };

    /// <summary>日誌目錄；為空時使用「數據目錄/logs」，相對路徑相對於內容根目錄。</summary>
    public string? Directory { get; set; }

    /// <summary>是否寫日誌文件。安裝版以系統服務運行時沒有控制台，日誌文件是診斷的唯一來源，默認開啟。</summary>
    public bool WriteToFile { get; set; } = true;

    public bool WriteToConsole { get; set; } = true;

    /// <summary>保留的日誌文件數（按天滾動，單個文件超過上限時另起新文件）。</summary>
    public int RetainedFileCount { get; set; } = 14;

    public long FileSizeLimitBytes { get; set; } = 10 * 1024 * 1024;
}

/// <summary>服務實際使用的日誌位置，供診斷包收集。</summary>
public sealed record ServiceLogLocation(string? Directory, string FilePrefix);

/// <summary>
/// 在代碼中配置 Serilog。不使用 Serilog.Settings.Configuration：它按配置反射加載輸出程序集，
/// 在 Native AOT 下會在啟動時直接拋出異常（找不到任何 Serilog 程序集）。
/// </summary>
internal static class ServiceLogging
{
    public const string FilePrefix = "service-";

    public static (LoggingOptions Options, ServiceLogLocation Location) Resolve(IConfiguration configuration, IHostEnvironment environment)
    {
        var options = configuration.GetSection(LoggingOptions.SectionName).Get<LoggingOptions>() ?? new LoggingOptions();
        string? directory = null;
        if (options.WriteToFile)
        {
            directory = string.IsNullOrWhiteSpace(options.Directory)
                ? Path.Combine(
                    StorageOptions.ResolveDataDirectory(configuration["NetworkControl:Storage:DataDirectory"], environment.ContentRootPath),
                    "logs")
                : StorageOptions.ResolveDataDirectory(options.Directory, environment.ContentRootPath);
        }

        return (options, new ServiceLogLocation(directory, FilePrefix));
    }

    public static LoggerConfiguration Apply(LoggerConfiguration logger, LoggingOptions options, ServiceLogLocation location)
    {
        logger.MinimumLevel.Is(ParseLevel(options.MinimumLevel, LogEventLevel.Information));
        foreach (var (source, level) in options.Overrides)
        {
            logger.MinimumLevel.Override(source, ParseLevel(level, LogEventLevel.Information));
        }

        if (options.WriteToConsole)
        {
            logger.WriteTo.Console(formatProvider: System.Globalization.CultureInfo.InvariantCulture);
        }

        if (location.Directory is { } directory)
        {
            logger.WriteTo.File(
                Path.Combine(directory, location.FilePrefix + ".log"),
                formatProvider: System.Globalization.CultureInfo.InvariantCulture,
                outputTemplate: "{Timestamp:yyyy-MM-dd HH:mm:ss.fff zzz} [{Level:u3}] {SourceContext}: {Message:lj}{NewLine}{Exception}",
                rollingInterval: RollingInterval.Day,
                rollOnFileSizeLimit: true,
                fileSizeLimitBytes: options.FileSizeLimitBytes,
                retainedFileCountLimit: options.RetainedFileCount,
                // 允許診斷包在服務運行時讀取正在寫入的文件
                shared: true);
        }

        return logger;
    }

    private static LogEventLevel ParseLevel(string? value, LogEventLevel fallback) =>
        Enum.TryParse<LogEventLevel>(value, ignoreCase: true, out var level) ? level : fallback;
}
