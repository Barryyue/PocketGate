using System.Text;
using Microsoft.AspNetCore.DataProtection.KeyManagement;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Core.Hosting;
using NetworkControl.Ipc;
using NetworkControl.Service;
using NetworkControl.Service.Diagnostics;
using NetworkControl.Service.Engines;
using NetworkControl.Service.Grpc;
using NetworkControl.Service.Ipc;
using NetworkControl.Service.Security;
using NetworkControl.Storage;
using Serilog;

// 控制台輸出統一使用 UTF-8（被重定向到文件或 journald 時不出現亂碼）
Console.OutputEncoding = Encoding.UTF8;

// 啟動階段的日誌：在配置加載完成之前也能記錄致命錯誤
Log.Logger = new LoggerConfiguration().WriteTo.Console().CreateBootstrapLogger();

try
{
    var builder = WebApplication.CreateSlimBuilder(args);

    // 可攜版：未顯式指定數據目錄時，默認使用程序目錄的 data（規則、日誌都在其中）。
    // 命令行 / 環境變量中的配置優先；界面啟動服務時會顯式傳入相同的值。
    if (PortableLayout.IsActive && string.IsNullOrWhiteSpace(builder.Configuration["NetworkControl:Storage:DataDirectory"]))
    {
        builder.Configuration.AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["NetworkControl:Storage:DataDirectory"] = PortableLayout.DataDirectory,
        });
    }

    // 以 Windows 服務 / systemd 單元運行時自動集成生命週期和日誌；以控制台運行時不生效
    builder.Services.AddWindowsService(o => o.ServiceName = "NetworkControl");
    builder.Services.AddSystemd();
    // 優雅關閉的上限：流式接口會在停止時主動結束，這裡只是兜底，避免切換引擎、退出時長時間等待
    builder.Services.Configure<HostOptions>(o => o.ShutdownTimeout = TimeSpan.FromSeconds(5));

    var (loggingOptions, logLocation) = ServiceLogging.Resolve(builder.Configuration, builder.Environment);
    builder.Services.AddSingleton(logLocation);
    builder.Services.AddSerilog((services, logger) => ServiceLogging.Apply(logger, loggingOptions, logLocation)
        .ReadFrom.Services(services)
        .Enrich.FromLogContext());

    var serviceOptions = builder.Configuration.GetSection(ServiceOptions.SectionName).Get<ServiceOptions>() ?? new ServiceOptions();
    builder.Services.AddOptions<ServiceOptions>().Bind(builder.Configuration.GetSection(ServiceOptions.SectionName));

    // IPC：只監聽本地命名管道 / Unix socket
    var endpoint = IpcServer.Configure(builder, serviceOptions);
    builder.Services.AddSingleton(endpoint);

    builder.Services
        .AddAuthentication(LocalPeerAuthenticationHandler.SchemeName)
        .AddScheme<Microsoft.AspNetCore.Authentication.AuthenticationSchemeOptions, LocalPeerAuthenticationHandler>(LocalPeerAuthenticationHandler.SchemeName, null);
    builder.Services.AddAuthorization(IpcPolicies.Configure);
    // 認證組件會順帶啟用 DataProtection；本服務不使用 Cookie / 令牌，密鑰只保存在內存中
    builder.Services.AddDataProtection();
    builder.Services.Configure<KeyManagementOptions>(o => o.XmlRepository = new InMemoryXmlRepository());
    builder.Services.AddGrpc(o => o.EnableDetailedErrors = builder.Environment.IsDevelopment());

    // 核心、存儲、引擎（註冊順序決定托管服務的啟動順序）
    builder.Services.AddNetworkControlCore(builder.Configuration);
    builder.Services.AddNetworkControlStorage(builder.Configuration);
    builder.Services.AddConfiguredEngine(serviceOptions);

    // 診斷包：服務概況和日誌；存儲、引擎各自註冊自己的收集者
    builder.Services.AddSingleton<ServiceStatusBuilder>();
    builder.Services.AddSingleton<ServiceDiagnosticsBuilder>();
    builder.Services.AddDiagnosticsContributor<ServiceSummaryContributor>();
    builder.Services.AddDiagnosticsContributor<ServiceLogsContributor>();

    var app = builder.Build();

    app.UseAuthentication();
    app.UseAuthorization();

    app.MapGrpcService<ControlGrpcService>();
    app.MapGrpcService<PromptsGrpcService>();
    app.MapGrpcService<RulesGrpcService>();
    app.MapGrpcService<ProfilesGrpcService>();
    app.MapGrpcService<ActivityGrpcService>();
    app.MapGrpcService<ConnectionsGrpcService>();
    app.MapGrpcService<AppsGrpcService>();
    app.MapGrpcService<DiagnosticsGrpcService>();
    app.MapEngineEndpoints(serviceOptions);

    app.Lifetime.ApplicationStarted.Register(() =>
        IpcServer.OnStarted(endpoint, app.Services.GetRequiredService<ILoggerFactory>().CreateLogger("NetworkControl.Ipc")));

    await app.RunAsync();
    return 0;
}
catch (Exception ex) when (ex is not HostAbortedException)
{
    Log.Fatal(ex, "服務異常終止");
    return 1;
}
finally
{
    await Log.CloseAndFlushAsync();
}
