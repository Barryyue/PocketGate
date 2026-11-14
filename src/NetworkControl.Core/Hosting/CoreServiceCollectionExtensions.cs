using System.Diagnostics.CodeAnalysis;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Pipeline;
using NetworkControl.Core.Profiles;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Hosting;

public static class CoreServiceCollectionExtensions
{
    /// <summary>配置節點名稱。</summary>
    public const string ConfigurationSection = "NetworkControl";

    /// <summary>
    /// 註冊核心服務。宿主還需要註冊：存儲實現（IRuleRepository、ISettingsRepository、IAppRepository）、
    /// 平台實現（INetworkEngine、IProcessInfoProvider、IAppInspector、IProcessExitWatcher）。
    /// </summary>
    public static IServiceCollection AddNetworkControlCore(this IServiceCollection services, IConfiguration configuration)
    {
        services.AddOptions<DnsMapperOptions>().Bind(configuration.GetSection($"{ConfigurationSection}:Dns"));

        services.TryAddSingleton(TimeProvider.System);
        services.TryAddSingleton<CoreMetrics>();

        // 狀態與設置
        services.TryAddSingleton<SettingsService>();
        services.TryAddSingleton<RuntimeStateService>();
        services.TryAddSingleton<ProfileService>();

        // 規則
        services.TryAddSingleton<RuleEngine>();
        services.TryAddSingleton<RuleService>();

        // 解析
        services.TryAddSingleton<DnsMapper>();
        services.TryAddSingleton<AppIdentityResolver>();
        services.TryAddSingleton<IAppIdentityResolver>(sp => sp.GetRequiredService<AppIdentityResolver>());

        // 詢問
        services.TryAddSingleton<PromptBroker>();

        // 活動日誌：消費者通過 TryAddEnumerable 註冊，存儲層等可以追加自己的消費者
        services.TryAddSingleton<LiveActivityFeed>();
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IActivityConsumer, LiveActivityFeed>(sp => sp.GetRequiredService<LiveActivityFeed>()));
        services.TryAddSingleton<IActivitySink, ActivityHub>();

        // 判決管線及內置階段
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IVerdictStage, DisabledModeStage>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IVerdictStage, BuiltinTrafficStage>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IVerdictStage, BlockAllStage>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IVerdictStage, RuleStage>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IVerdictStage, FallbackStage>());
        services.TryAddSingleton<VerdictPipeline>();

        // 流量統計（存儲層註冊 ITrafficRepository；沒有時只丟棄樣本）
        services.TryAddSingleton<Traffic.TrafficAggregator>();

        // 引擎宿主
        services.TryAddSingleton<EngineStatus>();
        services.TryAddSingleton<EngineHost>();
        services.TryAddSingleton<IEngineHost>(sp => sp.GetRequiredService<EngineHost>());

        // 啟動任務
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IStartupTask, LoadSettingsTask>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IStartupTask, LoadRuntimeStateTask>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IStartupTask, LoadProfilesTask>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IStartupTask, LoadRulesTask>());

        // 托管服務：註冊順序即啟動順序 —— 先執行啟動任務，再啟動生命週期管理和引擎
        services.AddHostedService<StartupTaskRunner>();
        services.AddHostedService<RuleLifetimeManager>();
        services.AddHostedService<EngineHostedService>();
        services.AddHostedService(sp => sp.GetRequiredService<Traffic.TrafficAggregator>());

        return services;
    }

    /// <summary>添加自定義判決階段。</summary>
    public static IServiceCollection AddVerdictStage<[DynamicallyAccessedMembers(DynamicallyAccessedMemberTypes.PublicConstructors)] TStage>(this IServiceCollection services)
        where TStage : class, IVerdictStage
    {
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IVerdictStage, TStage>());
        return services;
    }

    /// <summary>添加啟動任務。</summary>
    public static IServiceCollection AddStartupTask<[DynamicallyAccessedMembers(DynamicallyAccessedMemberTypes.PublicConstructors)] TTask>(this IServiceCollection services)
        where TTask : class, IStartupTask
    {
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IStartupTask, TTask>());
        return services;
    }

    /// <summary>添加活動事件消費者。</summary>
    public static IServiceCollection AddActivityConsumer<[DynamicallyAccessedMembers(DynamicallyAccessedMemberTypes.PublicConstructors)] TConsumer>(this IServiceCollection services)
        where TConsumer : class, IActivityConsumer
    {
        services.TryAddSingleton<TConsumer>();
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IActivityConsumer, TConsumer>(sp => sp.GetRequiredService<TConsumer>()));
        return services;
    }
}
