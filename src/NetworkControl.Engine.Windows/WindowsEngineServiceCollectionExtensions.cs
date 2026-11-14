using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Core.Engine;
using NetworkControl.Engine.Windows.Platform;

namespace NetworkControl.Engine.Windows;

public static class WindowsEngineServiceCollectionExtensions
{
    /// <summary>註冊 Windows WFP 引擎及其配套的平台服務（進程信息、簽名校驗、進程退出監聽）。</summary>
    public static IServiceCollection AddWindowsNetworkEngine(this IServiceCollection services)
    {
        services.TryAddSingleton<DevicePathResolver>();
        services.TryAddSingleton<WindowsProcessInfoProvider>();
        services.TryAddSingleton<IProcessInfoProvider>(sp => sp.GetRequiredService<WindowsProcessInfoProvider>());
        services.TryAddSingleton<IAppInspector, WindowsAppInspector>();
        services.TryAddSingleton<IProcessExitWatcher, WindowsProcessExitWatcher>();
        services.TryAddSingleton<IConnectionTable, WindowsConnectionTable>();

        services.TryAddSingleton<WfpEngine>();
        services.TryAddSingleton<INetworkEngine>(sp => sp.GetRequiredService<WfpEngine>());
        services.AddDiagnosticsContributor(sp => sp.GetRequiredService<WfpEngine>());
        return services;
    }
}
