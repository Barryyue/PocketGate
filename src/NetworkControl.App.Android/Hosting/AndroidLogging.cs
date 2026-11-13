using System.Collections.Concurrent;
using System.Globalization;
using System.Text;
using System.Threading.Channels;
using Microsoft.Extensions.Logging;
using AndroidLog = global::Android.Util.Log;

namespace NetworkControl.Android.Hosting;

/// <summary>寫入 logcat（標籤 NetworkControl），adb logcat -s NetworkControl 查看。</summary>
internal sealed class LogcatLoggerProvider : ILoggerProvider
{
    public const string Tag = "NetworkControl";

    private readonly ConcurrentDictionary<string, LogcatLogger> _loggers = new(StringComparer.Ordinal);

    public ILogger CreateLogger(string categoryName) => _loggers.GetOrAdd(categoryName, c => new LogcatLogger(ShortName(c)));

    public void Dispose() => _loggers.Clear();

    internal static string ShortName(string category)
    {
        var dot = category.LastIndexOf('.');
        return dot >= 0 ? category[(dot + 1)..] : category;
    }

    private sealed class LogcatLogger(string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state)
            where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Debug;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel))
            {
                return;
            }

            var message = $"[{category}] {formatter(state, exception)}";
            if (exception is not null)
            {
                message += Environment.NewLine + exception;
            }

            switch (logLevel)
            {
                case LogLevel.Trace or LogLevel.Debug:
                    AndroidLog.Debug(Tag, message);
                    break;
                case LogLevel.Information:
                    AndroidLog.Info(Tag, message);
                    break;
                case LogLevel.Warning:
                    AndroidLog.Warn(Tag, message);
                    break;
                default:
                    AndroidLog.Error(Tag, message);
                    break;
            }
        }
    }
}

/// <summary>
/// 按天滾動的日誌文件（<c>logs/app-yyyyMMdd.log</c>，保留 7 天）。寫入在後台任務中進行，不阻塞調用線程。
/// </summary>
internal sealed class FileLoggerProvider : ILoggerProvider
{
    private const int RetainedDays = 7;

    private readonly string _directory;
    private readonly Channel<string> _queue = Channel.CreateBounded<string>(new BoundedChannelOptions(10_000) { FullMode = BoundedChannelFullMode.DropWrite, SingleReader = true });
    private readonly Task _writer;

    public FileLoggerProvider(string directory)
    {
        _directory = directory;
        Directory.CreateDirectory(directory);
        _writer = Task.Run(WriteLoopAsync);
        DeleteOldFiles();
    }

    public ILogger CreateLogger(string categoryName) => new FileLogger(this, LogcatLoggerProvider.ShortName(categoryName));

    public void Dispose()
    {
        _queue.Writer.TryComplete();
        _writer.Wait(TimeSpan.FromSeconds(2));
    }

    private void Enqueue(string line) => _queue.Writer.TryWrite(line);

    private async Task WriteLoopAsync()
    {
        StreamWriter? writer = null;
        var currentDay = "";
        try
        {
            await foreach (var line in _queue.Reader.ReadAllAsync())
            {
                var day = DateTime.Now.ToString("yyyyMMdd", CultureInfo.InvariantCulture);
                if (day != currentDay || writer is null)
                {
                    if (writer is not null)
                    {
                        await writer.DisposeAsync();
                    }

                    currentDay = day;
                    var stream = new FileStream(Path.Combine(_directory, $"app-{day}.log"), FileMode.Append, FileAccess.Write, FileShare.ReadWrite);
                    writer = new StreamWriter(stream, new UTF8Encoding(false)) { AutoFlush = false };
                }

                await writer.WriteLineAsync(line);
                if (_queue.Reader.Count == 0)
                {
                    await writer.FlushAsync();
                }
            }
        }
        catch (IOException)
        {
            // 存儲不可用時放棄文件日誌，logcat 仍然可用
        }
        finally
        {
            if (writer is not null)
            {
                await writer.DisposeAsync();
            }
        }
    }

    private void DeleteOldFiles()
    {
        try
        {
            var cutoff = DateTime.Now.AddDays(-RetainedDays);
            foreach (var file in new DirectoryInfo(_directory).EnumerateFiles("app-*.log"))
            {
                if (file.LastWriteTime < cutoff)
                {
                    file.Delete();
                }
            }
        }
        catch (IOException)
        {
            // 忽略
        }
    }

    private sealed class FileLogger(FileLoggerProvider owner, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state)
            where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Information;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel))
            {
                return;
            }

            var line = string.Create(CultureInfo.InvariantCulture, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{Level(logLevel)}] {category}: {formatter(state, exception)}");
            if (exception is not null)
            {
                line += Environment.NewLine + exception;
            }

            owner.Enqueue(line);
        }

        private static string Level(LogLevel level) => level switch
        {
            LogLevel.Information => "INF",
            LogLevel.Warning => "WRN",
            LogLevel.Error => "ERR",
            LogLevel.Critical => "CRT",
            _ => "DBG",
        };
    }
}
