using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Core.Hosting;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Storage.Activity;
using NetworkControl.Storage.Diagnostics;
using NetworkControl.Storage.Repositories;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage;

public static class StorageServiceCollectionExtensions
{
    public static IServiceCollection AddNetworkControlStorage(this IServiceCollection services, IConfiguration configuration)
    {
        services.AddOptions<StorageOptions>()
            .Bind(configuration.GetSection($"{CoreServiceCollectionExtensions.ConfigurationSection}:Storage"))
            .PostConfigure<IHostEnvironment>((options, env) =>
                options.DataDirectory = StorageOptions.ResolveDataDirectory(options.DataDirectory, env.ContentRootPath));

        services.TryAddSingleton<ConfigDatabase>();
        services.TryAddSingleton<ActivityDatabase>();

        services.TryAddSingleton<IRuleRepository, SqliteRuleRepository>();
        services.TryAddSingleton<ISettingsRepository, SqliteSettingsRepository>();
        services.TryAddSingleton<IAppRepository, SqliteAppRepository>();
        services.TryAddSingleton<IActivityQuery, SqliteActivityQuery>();

        services.AddActivityConsumer<ActivityLogWriter>();
        services.AddHostedService(sp => sp.GetRequiredService<ActivityLogWriter>());

        services.AddStartupTask<StorageStartupTask>();
        services.AddDiagnosticsContributor<StorageDiagnosticsContributor>();
        return services;
    }
}

/// <summary>啟動時創建數據目錄並執行數據庫遷移。</summary>
internal sealed partial class StorageStartupTask(
    ConfigDatabase config,
    ActivityDatabase activity,
    IOptions<StorageOptions> options,
    ILogger<StorageStartupTask> logger) : IStartupTask
{
    public int Order => StartupOrder.Storage;

    public async Task ExecuteAsync(CancellationToken cancellationToken)
    {
        var directory = options.Value.ResolveDataDirectory();
        Directory.CreateDirectory(directory);
        LogDataDirectory(logger, directory);

        await Migrator.MigrateAsync(config, logger, cancellationToken);
        await Migrator.MigrateAsync(activity, logger, cancellationToken);
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "數據目錄：{Directory}")]
    private static partial void LogDataDirectory(ILogger logger, string directory);
}
