using global::Android.Content;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using NetworkControl.Engine.Vpn;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Android;

public static class AndroidEngineServiceCollectionExtensions
{
    /// <summary>
    /// 註冊 Android 黑洞式 VPN 引擎和全部平台實現。宿主還需要註冊 <see cref="IVpnForegroundNotifier"/>。
    /// </summary>
    public static IServiceCollection AddAndroidVpnEngine(this IServiceCollection services, Context context)
    {
        ArgumentNullException.ThrowIfNull(context);
        var appContext = context.ApplicationContext ?? context;

        services.TryAddSingleton(appContext);
        services.TryAddSingleton<AndroidAppCatalog>();
        services.TryAddSingleton<IInstalledAppCatalog>(sp => sp.GetRequiredService<AndroidAppCatalog>());
        services.TryAddSingleton<IConnectionOwnerResolver, AndroidConnectionOwnerResolver>();
        services.TryAddSingleton<AndroidVpnTunnel>();
        services.TryAddSingleton<IVpnTunnel>(sp => sp.GetRequiredService<AndroidVpnTunnel>());
        services.TryAddSingleton<IProtectionSwitch, SharedPreferencesProtectionSwitch>();

        // 流量統計：允許的應用不經過 VPN，只能讀取系統的按 UID 統計
        services.TryAddSingleton<AndroidUidTrafficSource>();
        services.TryAddSingleton<IUidTrafficSource>(sp => sp.GetRequiredService<AndroidUidTrafficSource>());
        services.TryAddSingleton(sp => new UidTrafficCollector(
            sp.GetRequiredService<IUidTrafficSource>(),
            sp.GetRequiredService<IInstalledAppCatalog>(),
            sp.GetRequiredService<Core.Traffic.TrafficAggregator>(),
            sp.GetRequiredService<TimeProvider>(),
            sp.GetRequiredService<Microsoft.Extensions.Logging.ILogger<UidTrafficCollector>>(),
            Path.Combine(appContext.FilesDir?.AbsolutePath ?? Path.GetTempPath(), "data", "uid-traffic.json")));
        services.AddHostedService(sp => sp.GetRequiredService<UidTrafficCollector>());

        return services.AddVpnBlackholeEngine(o => o.SelfPackage = appContext.PackageName ?? "");
    }
}
