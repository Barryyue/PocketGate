using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Engine;

namespace NetworkControl.Engine.Fake;

public static class FakeEngineServiceCollectionExtensions
{
    /// <summary>註冊模擬引擎及其配套的平台服務假實現。</summary>
    public static IServiceCollection AddFakeNetworkEngine(this IServiceCollection services)
    {
        services.TryAddSingleton<FakeProcessTable>();
        services.TryAddSingleton<IProcessInfoProvider>(sp => sp.GetRequiredService<FakeProcessTable>());
        services.TryAddSingleton<IAppInspector>(sp => sp.GetRequiredService<FakeProcessTable>());
        services.TryAddSingleton<IProcessExitWatcher>(sp => sp.GetRequiredService<FakeProcessTable>());
        services.TryAddSingleton<FakeConnectionTable>();
        services.TryAddSingleton<IConnectionTable>(sp => sp.GetRequiredService<FakeConnectionTable>());

        services.TryAddSingleton<FakeNetworkEngine>();
        services.TryAddSingleton<INetworkEngine>(sp => sp.GetRequiredService<FakeNetworkEngine>());
        services.TryAddSingleton<INetworkSimulator>(sp => sp.GetRequiredService<FakeNetworkEngine>());
        return services;
    }
}
