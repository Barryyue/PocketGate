using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Profiles;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Hosting;

/// <summary>
/// 【擴展點】啟動任務。在網絡引擎啟動之前按 <see cref="Order"/> 依次執行，
/// 例如數據庫遷移、加載設置和規則。任務失敗會阻止服務啟動。
/// </summary>
public interface IStartupTask
{
    int Order { get; }

    Task ExecuteAsync(CancellationToken cancellationToken);
}

/// <summary>啟動任務順序常量。</summary>
public static class StartupOrder
{
    public const int Storage = 0;
    public const int Settings = 100;
    public const int RuntimeState = 110;
    public const int Profiles = 120;
    public const int Rules = 200;
}

/// <summary>執行全部啟動任務。必須在 <see cref="Engine.EngineHostedService"/> 之前註冊。</summary>
public sealed partial class StartupTaskRunner(IEnumerable<IStartupTask> tasks, ILogger<StartupTaskRunner> logger) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        foreach (var task in tasks.OrderBy(t => t.Order))
        {
            LogRunning(logger, task.GetType().Name);
            await task.ExecuteAsync(cancellationToken);
        }
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    [LoggerMessage(Level = LogLevel.Debug, Message = "執行啟動任務 {Task}")]
    private static partial void LogRunning(ILogger logger, string task);
}

internal sealed class LoadSettingsTask(SettingsService settings) : IStartupTask
{
    public int Order => StartupOrder.Settings;

    public Task ExecuteAsync(CancellationToken cancellationToken) => settings.InitializeAsync(cancellationToken);
}

internal sealed class LoadRuntimeStateTask(RuntimeStateService state) : IStartupTask
{
    public int Order => StartupOrder.RuntimeState;

    public Task ExecuteAsync(CancellationToken cancellationToken) => state.InitializeAsync(cancellationToken);
}

internal sealed class LoadProfilesTask(ProfileService profiles) : IStartupTask
{
    public int Order => StartupOrder.Profiles;

    public Task ExecuteAsync(CancellationToken cancellationToken) => profiles.InitializeAsync(cancellationToken);
}

internal sealed class LoadRulesTask(RuleService rules) : IStartupTask
{
    public int Order => StartupOrder.Rules;

    public Task ExecuteAsync(CancellationToken cancellationToken) => rules.InitializeAsync(cancellationToken);
}
