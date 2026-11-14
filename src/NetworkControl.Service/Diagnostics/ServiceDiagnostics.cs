using System.Diagnostics;
using System.Globalization;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Diagnostics;
using NetworkControl.Ipc;

namespace NetworkControl.Service.Diagnostics;

/// <summary>
/// 生成服務端診斷包：依次調用所有 <see cref="IDiagnosticsContributor"/>，每個寫入自己的目錄。
/// 單個收集者失敗只記錄到清單中，不影響其他部分。
/// </summary>
internal sealed partial class ServiceDiagnosticsBuilder(IEnumerable<IDiagnosticsContributor> contributors, ILogger<ServiceDiagnosticsBuilder> logger)
{
    public async Task BuildAsync(Stream destination, DiagnosticsCollectOptions options, CancellationToken cancellationToken)
    {
        using var archive = new DiagnosticsArchive(destination, DiagnosticsRedactor.ForCurrentProcess(), "NetworkControl service diagnostics");
        foreach (var contributor in contributors)
        {
            try
            {
                await contributor.CollectAsync(new ArchiveSink(archive, contributor.Name), options, cancellationToken);
            }
            catch (Exception ex) when (ex is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
            {
                LogContributorFailed(logger, ex, contributor.Name);
                archive.AddError(contributor.Name, ex);
            }
        }
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "診斷信息收集失敗：{Contributor}")]
    private static partial void LogContributorFailed(ILogger logger, Exception exception, string contributor);

    /// <summary>把收集者的相對路徑映射到它自己的目錄下。</summary>
    private sealed class ArchiveSink(DiagnosticsArchive archive, string prefix) : IDiagnosticsSink
    {
        public void AddText(string path, string content) => archive.AddText(Combine(path), content);

        public Task AddFileAsync(string path, string sourcePath, CancellationToken cancellationToken) =>
            archive.AddFileAsync(Combine(path), sourcePath, cancellationToken);

        public Task AddBinaryAsync(string path, Stream content, CancellationToken cancellationToken) =>
            archive.AddBinaryAsync(Combine(path), content, cancellationToken);

        public void AddNote(string message) => archive.AddNote($"{prefix}: {message}");

        private string Combine(string path) => $"{prefix}/{path}";
    }
}

/// <summary>服務概況：版本、運行環境、引擎和模式、設置、生效的配置（只包含 NetworkControl 自己的配置節點）。</summary>
internal sealed class ServiceSummaryContributor(
    IHostEnvironment environment,
    IConfiguration configuration,
    IpcEndpoint endpoint,
    ServiceLogLocation logs,
    EngineStatus engine,
    RuntimeStateService state,
    SettingsService settings,
    RuleEngine rules,
    PromptBroker prompts,
    TimeProvider time) : IDiagnosticsContributor
{
    public string Name => "status";

    public Task CollectAsync(IDiagnosticsSink sink, DiagnosticsCollectOptions options, CancellationToken cancellationToken)
    {
        sink.AddText("summary.txt", BuildSummary());
        sink.AddText("configuration.txt", BuildConfiguration());
        if (options.IncludeConfiguration)
        {
            // 內存中的有效規則集：包括只在內存中的臨時規則（直到重啟 / 進程退出），數據庫副本裡沒有
            var text = new StringBuilder();
            foreach (var rule in rules.Current.Rules)
            {
                text.AppendLine(rule.ToString()).AppendLine();
            }

            sink.AddText("rules-effective.txt", text.ToString());
        }

        return Task.CompletedTask;
    }

    private string BuildSummary()
    {
        using var process = Process.GetCurrentProcess();
        var now = time.GetUtcNow();
        var version = typeof(ServiceSummaryContributor).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "?";
        var snapshot = state.Snapshot;
        var text = new StringBuilder();
        void Line(string name, object? value) => text.AppendLine(CultureInfo.InvariantCulture, $"{name,-24}{value}");

        text.AppendLine("[Service]");
        Line("Version", version);
        Line("IPC protocol", IpcEndpoint.ProtocolVersion);
        Line("Environment", environment.EnvironmentName);
        Line("Process", $"{Environment.ProcessPath} (pid {Environment.ProcessId})");
        Line("Started (UTC)", process.StartTime.ToUniversalTime().ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture));
        Line("Now (UTC)", now.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture));
        Line("Account", $"{Environment.UserDomainName}\\{Environment.UserName}");
        Line("Privileged", Environment.IsPrivilegedProcess);
        Line("Native AOT", !RuntimeFeature.IsDynamicCodeSupported);
        Line("Content root", environment.ContentRootPath);
        Line("IPC endpoint", endpoint);
        Line("Log directory", logs.Directory ?? "(file logging disabled)");
        Line("Working set", $"{process.WorkingSet64 / 1024 / 1024} MB");
        Line("Threads", process.Threads.Count);

        text.AppendLine().AppendLine("[System]");
        Line("OS", RuntimeInformation.OSDescription);
        Line("OS version", Environment.OSVersion.VersionString);
        Line("Architecture", RuntimeInformation.OSArchitecture);
        Line("Runtime", RuntimeInformation.FrameworkDescription);
        Line("Processors", Environment.ProcessorCount);
        Line("Culture", CultureInfo.CurrentCulture.Name);
        Line("Time zone", TimeZoneInfo.Local.Id);

        text.AppendLine().AppendLine("[Engine]");
        Line("Name", engine.Name);
        Line("State", engine.State);
        Line("Capabilities", engine.Capabilities);
        if (engine.LastFault is { } fault)
        {
            Line("Last fault", $"{fault.Code} (fatal: {fault.IsFatal}): {fault.Message}");
            if (fault.Exception is { } ex)
            {
                text.AppendLine(ex.ToString());
            }
        }

        text.AppendLine().AppendLine("[State]");
        Line("Mode", snapshot.Mode);
        Line("Active profile", snapshot.ActiveProfileId);
        Line("Do not disturb until", snapshot.DoNotDisturbUntil?.ToString("u", CultureInfo.InvariantCulture) ?? "-");
        Line("Active rules", rules.Current.Rules.Count);
        Line("Pending prompts", prompts.PendingFlowCount);

        text.AppendLine().AppendLine("[Settings]");
        text.AppendLine(settings.Current.ToString());
        return text.ToString();
    }

    /// <summary>生效的配置值（合併了 appsettings、環境變量、命令行）。只輸出本程序的節點，避免帶出無關的環境變量。</summary>
    private string BuildConfiguration()
    {
        var text = new StringBuilder();
        foreach (var (key, value) in configuration.AsEnumerable().Where(kv => kv.Value is not null).OrderBy(kv => kv.Key, StringComparer.OrdinalIgnoreCase))
        {
            if (key.StartsWith("NetworkControl:", StringComparison.OrdinalIgnoreCase)
                || key.StartsWith("ASPNETCORE_", StringComparison.OrdinalIgnoreCase)
                || key.StartsWith("DOTNET_", StringComparison.OrdinalIgnoreCase)
                || key.Equals("environment", StringComparison.OrdinalIgnoreCase)
                || key.Equals("contentRoot", StringComparison.OrdinalIgnoreCase))
            {
                text.AppendLine(CultureInfo.InvariantCulture, $"{key} = {value}");
            }
        }

        return text.ToString();
    }
}

/// <summary>服務日誌：最近的幾個文件（每個文件按上限保留末尾部分）。</summary>
internal sealed class ServiceLogsContributor(ServiceLogLocation location) : IDiagnosticsContributor
{
    private const int MaxFiles = 6;

    public string Name => "logs";

    public async Task CollectAsync(IDiagnosticsSink sink, DiagnosticsCollectOptions options, CancellationToken cancellationToken)
    {
        if (location.Directory is not { } directory)
        {
            sink.AddNote("file logging is disabled (NetworkControl:Logging:WriteToFile = false)");
            return;
        }

        if (!Directory.Exists(directory))
        {
            sink.AddNote($"log directory does not exist: {directory}");
            return;
        }

        var files = new DirectoryInfo(directory).GetFiles(location.FilePrefix + "*.log")
            .OrderByDescending(f => f.LastWriteTimeUtc)
            .Take(MaxFiles);
        foreach (var file in files)
        {
            await sink.AddFileAsync(file.Name, file.FullName, cancellationToken);
        }
    }
}
