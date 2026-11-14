using System.Diagnostics;
using System.Globalization;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using Grpc.Core;
using NetworkControl.App.Infrastructure;
using NetworkControl.Ipc;
using NetworkControl.Diagnostics;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Services;

public sealed record DiagnosticsExportOptions(bool IncludeConfiguration, bool IncludeActivity);

/// <summary>導出結果：服務部分是否成功收集（失敗時診斷包中只有界面側能讀到的內容）。</summary>
public sealed record DiagnosticsExportResult(bool ServiceIncluded, string? ServiceError);

/// <summary>
/// 導出時與服務的連接情況。界面中來自 <see cref="ServiceConnection"/>；
/// 命令行導出（界面無法啟動時）由 <see cref="ConnectDirectAsync"/> 直接連接一次。
/// </summary>
public sealed record DiagnosticsContext(IpcClients? Clients, string ConnectionState, ConnectionFailure? LastFailure, Proto.ServiceStatus? Status)
{
    public static DiagnosticsContext From(ServiceConnection connection) =>
        new(connection.Clients, connection.State.ToString(), connection.LastFailure, connection.Status);

    /// <summary>不經過界面的連接管理，直接嘗試各個端點一次。返回的通道由調用方釋放。</summary>
    public static async Task<(DiagnosticsContext Context, IDisposable? Channel)> ConnectDirectAsync(CancellationToken cancellationToken)
    {
        ConnectionFailure? failure = null;
        foreach (var endpoint in IpcEndpoint.ResolveForClient())
        {
            if (!endpoint.MayBeAvailable)
            {
                failure ??= new ConnectionFailure(ConnectionFailureKind.NotRunning, endpoint);
                continue;
            }

            var channel = IpcChannelFactory.Create(endpoint);
            var clients = new IpcClients(channel);
            try
            {
                var status = await clients.Control.GetStatusAsync(new Google.Protobuf.WellKnownTypes.Empty(), deadline: DateTime.UtcNow.AddSeconds(5), cancellationToken: cancellationToken);
                return (new DiagnosticsContext(clients, "Connected", null, status), channel);
            }
            catch (Exception ex) when (ex is RpcException or IOException or HttpRequestException or TimeoutException or UnauthorizedAccessException)
            {
                failure = new ConnectionFailure(ConnectionFailureKind.Other, endpoint, ex.Message);
                channel.Dispose();
            }
        }

        return (new DiagnosticsContext(null, "Disconnected", failure, null), null);
    }
}

/// <summary>
/// 生成完整的診斷包（zip）。結構：
/// <list type="bullet">
/// <item><c>app/</c>：界面概況和界面日誌；</item>
/// <item><c>service/</c>：服務生成的診斷包（概況、配置、日誌、引擎狀態，可選規則和連接日誌）；
/// 服務不可達時改為直接讀取服務日誌目錄（<c>service-offline/</c>）；</item>
/// <item><c>windows/</c>：Windows 服務的註冊狀態、事件日誌中與本程序相關的崩潰記錄。</item>
/// </list>
/// 服務出問題時最需要診斷包，所以任何一部分失敗都只記錄在清單中，不中斷導出。
/// </summary>
public sealed class DiagnosticsExporter(IServiceLauncher launcher, PreferencesStore preferences)
{
    private static readonly TimeSpan ServiceExportTimeout = TimeSpan.FromMinutes(2);

    private static readonly TimeSpan CommandTimeout = TimeSpan.FromSeconds(20);

    private const int MaxAppLogFiles = 5;

    private const int MaxOfflineServiceLogFiles = 4;

    public static string SuggestedFileName(DateTime now) =>
        string.Create(CultureInfo.InvariantCulture, $"NetworkControl-diagnostics-{now:yyyyMMdd-HHmmss}.zip");

    public async Task<DiagnosticsExportResult> ExportAsync(Stream destination, DiagnosticsExportOptions options, DiagnosticsContext connection, CancellationToken cancellationToken)
    {
        DiagnosticsExportResult result;
        using (var archive = new DiagnosticsArchive(destination, DiagnosticsRedactor.ForCurrentProcess(), $"NetworkControl diagnostics (UI {AppLog.Version})"))
        {
            archive.AddText("README.txt", Readme);
            await TryAsync(archive, "app/summary", () => archive.AddText("app/summary.txt", BuildAppSummary(connection)));
            await TryAsync(archive, "app/logs", () => AddAppLogsAsync(archive, cancellationToken));
            result = await AddServiceAsync(archive, options, connection, cancellationToken);
            if (OperatingSystem.IsWindows())
            {
                // 每個外部命令啟動要幾秒（終端安全軟件會掃描新進程）：並行運行，結果按固定順序寫入
                var service = CollectWindowsServiceAsync(cancellationToken);
                var events = CollectEventLogAsync(cancellationToken);
                await TryAsync(archive, "windows/service", async () => archive.AddText("windows/service.txt", await service));
                await TryAsync(archive, "windows/events", async () => archive.AddText("windows/events.txt", await events));
            }
        }

        AppLog.Info($"Diagnostics exported (service included: {result.ServiceIncluded}{(result.ServiceError is null ? "" : ", " + result.ServiceError)})");
        return result;
    }

    private const string Readme = """
        NetworkControl diagnostics / 診斷包

        app/              UI summary and UI logs / 界面概況與界面日誌
        service/          Service diagnostics (status, configuration, logs, engine state) / 服務診斷
        service-offline/  Service logs read directly when the service was unreachable / 服務不可達時直接讀取的服務日誌
        windows/          Windows service registration and related crash events / Windows 服務狀態與相關崩潰事件
        manifest.txt      Entries and notes (including anything that could not be collected) / 條目清單與收集說明

        User names and the computer name in text files are replaced with <user> / <machine>.
        文本中的用戶名和計算機名已替換為 <user> / <machine>。
        """;

    private string BuildAppSummary(DiagnosticsContext connection)
    {
        using var process = Process.GetCurrentProcess();
        var text = new StringBuilder();
        void Line(string name, object? value) => text.AppendLine(CultureInfo.InvariantCulture, $"{name,-24}{value}");

        text.AppendLine("[UI]");
        Line("Version", AppLog.Version);
        Line("IPC protocol", IpcEndpoint.ProtocolVersion);
        Line("Process", $"{Environment.ProcessPath} (pid {Environment.ProcessId})");
        Line("Started", process.StartTime.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture));
        Line("Now", DateTimeOffset.Now.ToString("yyyy-MM-dd HH:mm:ss zzz", CultureInfo.InvariantCulture));
        Line("Privileged", Environment.IsPrivilegedProcess);
        Line("Native AOT", !RuntimeFeature.IsDynamicCodeSupported);
        Line("Working set", $"{process.WorkingSet64 / 1024 / 1024} MB");
        Line("Launcher", launcher.Kind);
        if (PortableLayout.RootDirectory is { } portableRoot)
        {
            Line("Portable root", portableRoot);
        }
        Line("Language preference", preferences.Current.Language ?? "(system)");
        Line("Theme preference", preferences.Current.Theme);
        Line("Log directory", AppLog.Directory);

        text.AppendLine().AppendLine("[System]");
        Line("OS", RuntimeInformation.OSDescription);
        Line("Architecture", RuntimeInformation.OSArchitecture);
        Line("Runtime", RuntimeInformation.FrameworkDescription);
        Line("Culture / UI culture", $"{CultureInfo.CurrentCulture.Name} / {CultureInfo.CurrentUICulture.Name}");
        Line("Time zone", TimeZoneInfo.Local.Id);

        text.AppendLine().AppendLine("[Connection]");
        Line("State", connection.ConnectionState);
        foreach (var endpoint in IpcEndpoint.ResolveForClient())
        {
            Line("Endpoint", $"{endpoint} (exists: {endpoint.MayBeAvailable})");
        }

        if (connection.LastFailure is { } failure)
        {
            Line("Last failure", $"{failure.Kind} at {failure.Endpoint}; service protocol {failure.ServiceProtocol}; {failure.Detail}");
        }

        if (connection.Status is { } status)
        {
            Line("Service version", status.ServiceVersion);
            Line("Service protocol", status.ProtocolVersion);
            Line("Engine", $"{status.Engine.Name} {status.Engine.State} {status.Engine.FaultMessage}");
            Line("Mode", status.Mode);
            Line("Active rules", status.ActiveRuleCount);
            Line("Client is admin", status.Client?.IsAdmin);
        }

        text.AppendLine().AppendLine("[Processes]");
        foreach (var p in Process.GetProcesses().Where(p => p.ProcessName.StartsWith("NetworkControl", StringComparison.OrdinalIgnoreCase)))
        {
            using (p)
            {
                string path;
                try
                {
                    path = p.MainModule?.FileName ?? "?";
                }
                catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
                {
                    // 以 SYSTEM 運行的服務進程，普通用戶無法讀取模塊信息
                    path = "(access denied)";
                }

                Line(p.ProcessName, $"pid {p.Id}, {path}");
            }
        }

        return text.ToString();
    }

    private static async Task AddAppLogsAsync(DiagnosticsArchive archive, CancellationToken cancellationToken)
    {
        foreach (var file in AppLog.Files().Take(MaxAppLogFiles))
        {
            await archive.AddFileAsync($"app/logs/{file.Name}", file.FullName, cancellationToken);
        }
    }

    private static async Task<DiagnosticsExportResult> AddServiceAsync(DiagnosticsArchive archive, DiagnosticsExportOptions options, DiagnosticsContext connection, CancellationToken cancellationToken)
    {
        string? error;
        if (connection.Clients is { } clients)
        {
            try
            {
                using var buffer = new MemoryStream();
                using var call = clients.Diagnostics.Export(
                    new Proto.ExportDiagnosticsRequest { IncludeConfiguration = options.IncludeConfiguration, IncludeActivity = options.IncludeActivity },
                    deadline: DateTime.UtcNow.Add(ServiceExportTimeout),
                    cancellationToken: cancellationToken);
                await foreach (var chunk in call.ResponseStream.ReadAllAsync(cancellationToken))
                {
                    chunk.Data.WriteTo(buffer);
                }

                buffer.Position = 0;
                await archive.MergeAsync(buffer, "service", cancellationToken);
                return new DiagnosticsExportResult(true, null);
            }
            catch (RpcException ex)
            {
                // 例如舊版服務沒有該接口（Unimplemented）、非管理員（PermissionDenied）、服務在導出過程中退出
                error = $"{ex.StatusCode}: {ex.Status.Detail}";
            }
            catch (Exception ex) when (ex is IOException or InvalidDataException or HttpRequestException)
            {
                error = ex.Message;
            }
        }
        else
        {
            error = connection.LastFailure is { } failure ? $"not connected ({failure.Kind})" : "not connected";
        }

        archive.AddNote($"service diagnostics unavailable: {error}; falling back to reading service logs directly");
        await TryAsync(archive, "service-offline", () => AddOfflineServiceLogsAsync(archive, cancellationToken));
        return new DiagnosticsExportResult(false, error);
    }

    /// <summary>服務不可達時直接讀取服務日誌。安裝版在 %ProgramData%\NetworkControl\logs（普通用戶默認可讀），開發佈局在倉庫的 .devdata\logs。</summary>
    private static async Task AddOfflineServiceLogsAsync(DiagnosticsArchive archive, CancellationToken cancellationToken)
    {
        var found = false;
        foreach (var directory in CandidateServiceLogDirectories().Distinct(StringComparer.OrdinalIgnoreCase))
        {
            var info = new DirectoryInfo(directory);
            if (!info.Exists)
            {
                continue;
            }

            found = true;
            archive.AddNote($"service-offline: reading {directory}");
            foreach (var file in info.GetFiles("service-*.log").OrderByDescending(f => f.LastWriteTimeUtc).Take(MaxOfflineServiceLogFiles))
            {
                try
                {
                    await archive.AddFileAsync($"service-offline/{file.Name}", file.FullName, cancellationToken);
                }
                catch (UnauthorizedAccessException ex)
                {
                    archive.AddError($"service-offline/{file.Name}", ex);
                }
            }
        }

        if (!found)
        {
            archive.AddNote("service-offline: no service log directory found");
        }
    }

    private static IEnumerable<string> CandidateServiceLogDirectories()
    {
        // 可攜版：<程序目錄>/data/logs
        if (PortableLayout.IsActive && PortableLayout.DataDirectory is { } data)
        {
            yield return Path.Combine(data, "logs");
        }

        if (OperatingSystem.IsWindows())
        {
            yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "NetworkControl", "logs");
        }
        else
        {
            yield return OperatingSystem.IsMacOS() ? "/Library/Application Support/NetworkControl/logs" : "/var/lib/netcontrol/logs";
        }

        // 開發佈局：src/NetworkControl.Service/bin/... → 倉庫根目錄/.devdata/logs
        if (ServiceLauncher.FindDevelopmentService(AppContext.BaseDirectory) is { } devService)
        {
            var binIndex = devService.LastIndexOf($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}", StringComparison.OrdinalIgnoreCase);
            if (binIndex > 0)
            {
                yield return Path.GetFullPath(Path.Combine(devService[..binIndex], "..", "..", ".devdata", "logs"));
            }
        }
    }

    private static async Task<string> CollectWindowsServiceAsync(CancellationToken cancellationToken)
    {
        if (PortableLayout.IsActive)
        {
            return "Portable mode: the core service runs as a normal process, not as a Windows service.\n";
        }

        string[] commands = ["queryex", "qc", "qfailure"];
        var outputs = await Task.WhenAll(commands.Select(c => RunAsync($"sc.exe {c} {ServiceLauncher.WindowsServiceName}", cancellationToken)));
        var text = new StringBuilder();
        for (var i = 0; i < commands.Length; i++)
        {
            text.AppendLine(CultureInfo.InvariantCulture, $"> sc {commands[i]} {ServiceLauncher.WindowsServiceName}")
                .AppendLine(outputs[i])
                .AppendLine();
        }

        return text.ToString();
    }

    /// <summary>
    /// 事件日誌中最近 7 天與本程序相關的記錄：應用程序崩潰（Application Error / .NET Runtime / WER）和服務控制管理器事件。
    /// 服務在記錄日誌之前就崩潰（例如原生代碼訪問衝突）時，這是唯一的線索。
    /// </summary>
    private static async Task<string> CollectEventLogAsync(CancellationToken cancellationToken)
    {
        const string window = "TimeCreated[timediff(@SystemTime) <= 604800000]";
        var queries = new (string Log, string Providers)[]
        {
            ("Application", "Provider[@Name='Application Error'] or Provider[@Name='.NET Runtime'] or Provider[@Name='Windows Error Reporting']"),
            ("System", "Provider[@Name='Service Control Manager']"),
        };

        // wevtutil 不跟隨控制台代碼頁，總是按系統 ANSI 代碼頁輸出（中文系統是 GBK）：直接運行並按該代碼頁解碼
        var outputs = await Task.WhenAll(queries.Select(q => RunProcessAsync(
            "wevtutil.exe", ["qe", q.Log, "/c:300", "/rd:true", "/f:text", $"/q:*[System[({q.Providers}) and {window}]]"], SystemAnsiEncoding(), cancellationToken)));
        var text = new StringBuilder();
        for (var i = 0; i < queries.Length; i++)
        {
            var log = queries[i].Log;
            var events = SplitEvents(outputs[i]).Where(e => e.Contains("NetworkControl", StringComparison.OrdinalIgnoreCase)).Take(50).ToList();
            text.AppendLine(CultureInfo.InvariantCulture, $"===== {log}: {events.Count} related event(s) in the last 7 days");
            foreach (var e in events)
            {
                text.AppendLine(e.Trim()).AppendLine();
            }
        }

        return text.ToString();
    }

    /// <summary>wevtutil 的文本格式以「Event[n]:」開始每一條事件。</summary>
    internal static IEnumerable<string> SplitEvents(string output)
    {
        var current = new StringBuilder();
        foreach (var line in output.Split('\n'))
        {
            if (line.StartsWith("Event[", StringComparison.Ordinal) && current.Length > 0)
            {
                yield return current.ToString();
                current.Clear();
            }

            current.Append(line).Append('\n');
        }

        if (current.Length > 0)
        {
            yield return current.ToString();
        }
    }

    /// <summary>
    /// 通過 cmd 運行命令並把控制台代碼頁切到 UTF-8：sc / wevtutil 按控制台代碼頁輸出（中文系統是 GBK），
    /// 直接讀取會亂碼。命令行手工構造（/s：cmd 只去掉最外層引號，查詢條件裡的引號和 &lt; 保持原樣）；命令都是固定字符串，不拼接外部輸入。
    /// </summary>
    private static Task<string> RunAsync(string command, CancellationToken cancellationToken) =>
        Run(new ProcessStartInfo("cmd.exe", $"/d /s /c \"chcp 65001 >nul && {command}\"") { StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8 }, cancellationToken);

    private static Task<string> RunProcessAsync(string fileName, string[] arguments, Encoding encoding, CancellationToken cancellationToken)
    {
        var info = new ProcessStartInfo(fileName) { StandardOutputEncoding = encoding, StandardErrorEncoding = encoding };
        foreach (var argument in arguments)
        {
            info.ArgumentList.Add(argument);
        }

        return Run(info, cancellationToken);
    }

    /// <summary>系統（非用戶區域格式）的 ANSI 代碼頁，例如中文系統的 936。.NET 默認只帶 UTF / Latin1，其他代碼頁要註冊提供程序。</summary>
    private static Encoding SystemAnsiEncoding()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        try
        {
            // 系統安裝語言決定系統代碼頁（與用戶的區域格式設置無關）
            return Encoding.GetEncoding(CultureInfo.InstalledUICulture.TextInfo.ANSICodePage);
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException)
        {
            return Encoding.UTF8;
        }
    }

    private static async Task<string> Run(ProcessStartInfo info, CancellationToken cancellationToken)
    {
        info.RedirectStandardOutput = true;
        info.RedirectStandardError = true;
        info.UseShellExecute = false;
        info.CreateNoWindow = true;
        var command = $"{info.FileName} {info.Arguments}{string.Join(' ', info.ArgumentList)}";

        using var process = Process.Start(info) ?? throw new InvalidOperationException($"Cannot start: {command}");
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(CommandTimeout);
        var stdout = process.StandardOutput.ReadToEndAsync(timeout.Token);
        var stderr = process.StandardError.ReadToEndAsync(timeout.Token);
        try
        {
            await process.WaitForExitAsync(timeout.Token);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            process.Kill(entireProcessTree: true);
            return $"(timed out after {CommandTimeout.TotalSeconds:0} s)";
        }

        var output = (await stdout).Replace("\r\n", "\n", StringComparison.Ordinal);
        var error = (await stderr).Trim();
        return error.Length > 0 ? $"{output}\n[stderr] {error}\n[exit code] {process.ExitCode}" : output;
    }

    private static async Task TryAsync(DiagnosticsArchive archive, string section, Func<Task> action)
    {
        try
        {
            await action();
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            archive.AddError(section, ex);
        }
    }

    private static Task TryAsync(DiagnosticsArchive archive, string section, Action action) =>
        TryAsync(archive, section, () =>
        {
            action();
            return Task.CompletedTask;
        });
}
