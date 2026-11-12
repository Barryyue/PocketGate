using NetworkControl.Engine.Fake;
using NetworkControl.Engine.Windows;
using NetworkControl.Service.Grpc;

namespace NetworkControl.Service.Engines;

/// <summary>
/// 按配置裝配網絡引擎及其配套的平台服務。新增平台引擎時：
/// 1. 新建 NetworkControl.Engine.&lt;Platform&gt; 項目，提供 Add&lt;Platform&gt;NetworkEngine() 擴展方法；
/// 2. 在 <see cref="EngineNames"/> 中添加名稱，並在 <see cref="Resolve"/> 和這裡添加分支。
/// </summary>
internal static class EngineRegistration
{
    /// <summary>把配置值解析為實際使用的引擎名稱（Auto → 當前平台的默認引擎）。</summary>
    public static string Resolve(ServiceOptions options) => options.Engine switch
    {
        var e when string.Equals(e, EngineNames.Auto, StringComparison.OrdinalIgnoreCase) =>
            OperatingSystem.IsWindows() ? EngineNames.Wfp : EngineNames.Fake,
        var e => e,
    };

    public static void AddConfiguredEngine(this IServiceCollection services, ServiceOptions options)
    {
        var engine = Resolve(options);
        if (string.Equals(engine, EngineNames.Fake, StringComparison.OrdinalIgnoreCase))
        {
            services.AddFakeNetworkEngine();
        }
        else if (string.Equals(engine, EngineNames.Wfp, StringComparison.OrdinalIgnoreCase) && OperatingSystem.IsWindows())
        {
            services.AddWindowsNetworkEngine();
        }
        else
        {
            throw new InvalidOperationException(
                $"未知或當前平台不支持的網絡引擎「{options.Engine}」。可用：{EngineNames.Auto}、{EngineNames.Fake}、{EngineNames.Wfp}（Windows）。");
        }
    }

    /// <summary>映射與引擎相關的開發用 gRPC 服務。</summary>
    public static void MapEngineEndpoints(this WebApplication app, ServiceOptions options)
    {
        if (string.Equals(Resolve(options), EngineNames.Fake, StringComparison.OrdinalIgnoreCase))
        {
            app.MapGrpcService<SimulatorGrpcService>();
        }
    }
}
