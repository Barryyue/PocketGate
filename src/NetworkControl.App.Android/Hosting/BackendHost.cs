using global::Android.Content;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using NetworkControl.Android.Platform;
using NetworkControl.Core.Hosting;
using NetworkControl.Engine.Android;
using NetworkControl.Storage;

namespace NetworkControl.Android.Hosting;

/// <summary>
/// 進程級的核心宿主（docs/android.md §4.2）：Core + Storage + VPN 引擎在同一進程中運行。
/// 惰性啟動：界面、開機廣播、系統啟動的 VPN 服務都通過 <see cref="EnsureStartedAsync"/> 取得同一個實例。
/// </summary>
public static partial class BackendHost
{
    private static readonly Lock Gate = new();
    private static Task<IHost>? _starting;

    /// <summary>已啟動的宿主；尚未啟動時為 null。</summary>
    public static IHost? Current { get; private set; }

    public static Task<IHost> EnsureStartedAsync(Context context)
    {
        lock (Gate)
        {
            // 在線程池上啟動：托管服務的後台循環不能捕獲界面線程的同步上下文
            var appContext = context.ApplicationContext ?? context;
            return _starting ??= Task.Run(() => StartAsync(appContext));
        }
    }

    /// <summary>取得已啟動的宿主中的服務；宿主未啟動時等待它啟動。</summary>
    public static async Task<T> GetAsync<T>(Context context)
        where T : notnull
    {
        var host = await EnsureStartedAsync(context);
        return host.Services.GetRequiredService<T>();
    }

    private static async Task<IHost> StartAsync(Context context)
    {
        var filesDir = context.FilesDir?.AbsolutePath ?? throw new InvalidOperationException("無法取得應用數據目錄。");
        var builder = Host.CreateApplicationBuilder(new HostApplicationBuilderSettings
        {
            DisableDefaults = true,
            ContentRootPath = filesDir,
            ApplicationName = "NetworkControl.Android",
            EnvironmentName = Environments.Production,
            Configuration = new ConfigurationManager(),
        });

        builder.Configuration.AddInMemoryCollection(new Dictionary<string, string?>
        {
            [$"{CoreServiceCollectionExtensions.ConfigurationSection}:Storage:DataDirectory"] = Path.Combine(filesDir, "data"),
        });

        builder.Logging.ClearProviders();
        builder.Logging.SetMinimumLevel(LogLevel.Debug);
        builder.Logging.AddProvider(new LogcatLoggerProvider());
        builder.Logging.AddProvider(new FileLoggerProvider(Path.Combine(filesDir, "logs")));

        // 進程由系統管理：不註冊控制台生命週期，停止由 Android 進程結束完成
        builder.Services.AddSingleton<IHostLifetime, NoopHostLifetime>();
        builder.Services.Configure<HostOptions>(o => o.ShutdownTimeout = TimeSpan.FromSeconds(3));
        builder.Services.AddMetrics();

        builder.Services.AddNetworkControlCore(builder.Configuration);
        builder.Services.AddNetworkControlStorage(builder.Configuration);
        builder.Services.AddSingleton<IVpnForegroundNotifier, VpnForegroundNotifier>();
        builder.Services.AddAndroidVpnEngine(context);

        builder.Services.AddSingleton<PromptNotifier>();
        builder.Services.AddHostedService(sp => sp.GetRequiredService<PromptNotifier>());
        builder.Services.AddSingleton<Backend>();
        builder.Services.AddSingleton<IBackend>(sp => sp.GetRequiredService<Backend>());
        builder.Services.AddSingleton<AppIconLoader>();

        var host = builder.Build();
        var logger = host.Services.GetRequiredService<ILoggerFactory>().CreateLogger("NetworkControl.Android.BackendHost");
        try
        {
            await host.StartAsync();
        }
        catch (Exception ex)
        {
            LogStartFailed(logger, ex);
            lock (Gate)
            {
                _starting = null;
            }

            host.Dispose();
            throw;
        }

        // 始終開啟的 VPN：系統直接啟動了服務時打開保護（引擎據此建立虛擬網卡）
        host.Services.GetRequiredService<AndroidVpnTunnel>().SystemStartRequested += (_, _) =>
            host.Services.GetRequiredService<Engine.Vpn.Platform.IProtectionSwitch>().SetEnabled(true);

        Current = host;
        LogStarted(logger, filesDir);
        return host;
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "核心已啟動，數據目錄 {Directory}")]
    private static partial void LogStarted(ILogger logger, string directory);

    [LoggerMessage(Level = LogLevel.Critical, Message = "核心啟動失敗")]
    private static partial void LogStartFailed(ILogger logger, Exception exception);

    private sealed class NoopHostLifetime : IHostLifetime
    {
        public Task WaitForStartAsync(CancellationToken cancellationToken) => Task.CompletedTask;

        public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
    }
}
