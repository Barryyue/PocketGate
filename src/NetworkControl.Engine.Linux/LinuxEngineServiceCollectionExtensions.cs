using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Engine;

namespace NetworkControl.Engine.Linux;

public static class LinuxEngineServiceCollectionExtensions
{
    /// <summary>
    /// 註冊 Linux NFQUEUE 引擎及核心要求的全部平台服務（INetworkEngine、IProcessInfoProvider、IAppInspector、IProcessExitWatcher），
    /// 缺任何一個服務都無法啟動。
    /// </summary>
    public static IServiceCollection AddLinuxNetworkEngine(this IServiceCollection services)
    {
        services.TryAddSingleton(TimeProvider.System);
        services.TryAddSingleton<LinuxProcessInfoProvider>();
        services.TryAddSingleton<IProcessInfoProvider>(sp => sp.GetRequiredService<LinuxProcessInfoProvider>());
        services.TryAddSingleton<IAppInspector, LinuxAppInspector>();
        services.TryAddSingleton<IProcessExitWatcher, LinuxProcessExitWatcher>();

        services.TryAddSingleton<NftablesController>();
        services.TryAddSingleton<NfqueueAdapter>();
        services.TryAddSingleton<LinuxNetworkEngine>();
        services.TryAddSingleton<INetworkEngine>(sp => sp.GetRequiredService<LinuxNetworkEngine>());
        return services;
    }
}
