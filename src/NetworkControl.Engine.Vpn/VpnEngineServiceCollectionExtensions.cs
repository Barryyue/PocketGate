using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Engine;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Vpn;

public static class VpnEngineServiceCollectionExtensions
{
    /// <summary>
    /// 註冊黑洞式 VPN 引擎及其配套的 Core 平台服務（應用檢查、進程信息）。
    /// 宿主還需要註冊平台實現：<see cref="IVpnTunnel"/>、<see cref="IInstalledAppCatalog"/>、
    /// <see cref="IConnectionOwnerResolver"/>、<see cref="IProtectionSwitch"/>。
    /// </summary>
    public static IServiceCollection AddVpnBlackholeEngine(this IServiceCollection services, Action<VpnEngineOptions> configure)
    {
        services.AddOptions<VpnEngineOptions>().Configure(configure);

        services.TryAddSingleton<IAppInspector, PackageAppInspector>();
        services.TryAddSingleton<NoProcessInfoProvider>();
        services.TryAddSingleton<IProcessInfoProvider>(sp => sp.GetRequiredService<NoProcessInfoProvider>());
        services.TryAddSingleton<IProcessExitWatcher>(sp => sp.GetRequiredService<NoProcessInfoProvider>());

        services.TryAddSingleton<VpnBlackholeEngine>();
        services.TryAddSingleton<INetworkEngine>(sp => sp.GetRequiredService<VpnBlackholeEngine>());
        return services;
    }
}
