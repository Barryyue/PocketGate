using System.ComponentModel;
using System.Diagnostics;
using NetworkControl.Ipc;

namespace NetworkControl.App.Services;

public enum ServiceLaunchKind
{
    /// <summary>無法從界面啟動（例如非 Windows 平台上未安裝）。</summary>
    None,

    /// <summary>開發佈局：與本界面並排的本地構建服務。</summary>
    Development,

    /// <summary>可攜版：發布包中與界面並排的 Service 目錄。</summary>
    Portable,

    /// <summary>已安裝為系統服務。</summary>
    SystemService,

    /// <summary>沒有找到可啟動的服務。</summary>
    NotInstalled,
}

/// <summary>從界面啟動核心服務的策略。</summary>
public interface IServiceLauncher
{
    ServiceLaunchKind Kind { get; }

    /// <summary>
    /// 啟動服務；返回 null 表示已發起啟動，否則返回錯誤信息。
    /// <paramref name="engine"/> 只在開發佈局下生效（Fake / Wfp），為 null 時使用服務的默認配置。
    /// </summary>
    Task<string?> StartAsync(string? engine, CancellationToken cancellationToken);
}

/// <summary>
/// 按運行環境選擇啟動方式：
/// 可攜版（根目錄有 portable.txt）直接拉起同目錄 Service 下的服務進程；
/// 開發佈局（src/NetworkControl.App/bin/... 旁邊有 src/NetworkControl.Service/bin/...）直接拉起服務進程；
/// Windows 上已安裝的服務通過 UAC 提權執行 <c>sc start</c>。
/// </summary>
public sealed class ServiceLauncher : IServiceLauncher
{
    public const string WindowsServiceName = "NetworkControl";

    private readonly string? _devExecutable;
    private readonly string? _portableExecutable;

    public ServiceLauncher()
    {
        _portableExecutable = PortableLayout.ServiceExecutable;
        _devExecutable = FindDevelopmentService(AppContext.BaseDirectory);
        Kind = _portableExecutable is not null ? ServiceLaunchKind.Portable
            : _devExecutable is not null ? ServiceLaunchKind.Development
            : OperatingSystem.IsWindows() ? (IsWindowsServiceInstalled() ? ServiceLaunchKind.SystemService : ServiceLaunchKind.NotInstalled)
            : ServiceLaunchKind.None;
    }

    public ServiceLaunchKind Kind { get; }

    public Task<string?> StartAsync(string? engine, CancellationToken cancellationToken)
    {
        try
        {
            switch (Kind)
            {
                case ServiceLaunchKind.Portable:
                    StartPortableService(_portableExecutable!);
                    return Task.FromResult<string?>(null);
                case ServiceLaunchKind.Development:
                    StartDevelopmentService(_devExecutable!, engine);
                    return Task.FromResult<string?>(null);
                case ServiceLaunchKind.SystemService:
                    return StartWindowsServiceAsync(cancellationToken);
                default:
                    return Task.FromResult<string?>("Not supported");
            }
        }
        catch (Exception ex) when (ex is Win32Exception or IOException or UnauthorizedAccessException)
        {
            // 包括用戶在 UAC 對話框中點了「否」，以及數據目錄不可寫
            return Task.FromResult<string?>(ex.Message);
        }
    }

    /// <summary>
    /// 啟動可攜版服務：工作目錄和內容根目錄設為 Service 目錄，數據目錄和 IPC 端點通過命令行傳遞。
    /// WFP 需要管理員權限，因此 Windows 上總是通過 runas 提權；提權後環境變量不一定保留，不能依賴環境變量。
    /// </summary>
    private static void StartPortableService(string executable)
    {
        // 服務已在運行（例如上次界面異常退出後留下的進程）：不要重複啟動
        if (IpcEndpoint.ResolveForClient().Any(e => e.MayBeAvailable))
        {
            return;
        }

        var dataDirectory = PortableLayout.DataDirectory
            ?? throw new InvalidOperationException("Portable data directory is not available.");
        var serviceDirectory = Path.GetDirectoryName(executable)
            ?? throw new InvalidOperationException("Portable service directory is not available.");

        // 先由界面（普通權限）創建目錄，保證界面日誌和偏好文件對當前用戶可寫
        Directory.CreateDirectory(dataDirectory);

        var elevate = OperatingSystem.IsWindows();
        var info = new ProcessStartInfo(executable)
        {
            WorkingDirectory = serviceDirectory,
            UseShellExecute = elevate,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
        };
        if (elevate)
        {
            info.Verb = "runas";
        }

        info.ArgumentList.Add("--contentRoot");
        info.ArgumentList.Add(serviceDirectory);
        info.ArgumentList.Add($"--NetworkControl:Storage:DataDirectory={dataDirectory}");
        info.ArgumentList.Add($"--NetworkControl:Service:IpcEndpoint={IpcEndpoint.ResolveForClient()[0]}");

        using var _ = Process.Start(info);
    }

    /// <summary>根據本界面的輸出目錄推導出同一配置下的服務輸出目錄。</summary>
    internal static string? FindDevelopmentService(string baseDirectory)
    {
        var marker = $"{Path.DirectorySeparatorChar}NetworkControl.App{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}";
        var index = baseDirectory.LastIndexOf(marker, StringComparison.OrdinalIgnoreCase);
        if (index < 0)
        {
            return null;
        }

        var serviceDir = string.Concat(
            baseDirectory.AsSpan(0, index),
            $"{Path.DirectorySeparatorChar}NetworkControl.Service{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}",
            baseDirectory.AsSpan(index + marker.Length));
        var exe = Path.Combine(serviceDir, OperatingSystem.IsWindows() ? "NetworkControl.Service.exe" : "NetworkControl.Service");
        return File.Exists(exe) ? exe : null;
    }

    /// <summary>
    /// 啟動開發服務。內容根目錄設為服務項目目錄（與 dotnet run 一致），appsettings.Development.json 中的
    /// 相對數據目錄因此指向倉庫根目錄的 .devdata。真實攔截（Wfp）需要管理員權限：通過 runas 觸發 UAC，
    /// 提權後環境變量和工作目錄不一定保留，所以環境、內容根目錄和引擎都通過命令行參數傳遞。
    /// </summary>
    private static void StartDevelopmentService(string executable, string? engine)
    {
        var binIndex = executable.LastIndexOf($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}", StringComparison.OrdinalIgnoreCase);
        var projectDir = binIndex > 0 ? executable[..binIndex] : Path.GetDirectoryName(executable)!;
        var elevate = string.Equals(engine, "Wfp", StringComparison.OrdinalIgnoreCase) && OperatingSystem.IsWindows();

        var info = new ProcessStartInfo(executable)
        {
            WorkingDirectory = projectDir,
            UseShellExecute = elevate,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
        };
        if (elevate)
        {
            info.Verb = "runas";
        }

        info.ArgumentList.Add("--environment");
        info.ArgumentList.Add("Development");
        info.ArgumentList.Add("--contentRoot");
        info.ArgumentList.Add(projectDir);
        if (engine is not null)
        {
            info.ArgumentList.Add($"--NetworkControl:Service:Engine={engine}");
        }

        using var _ = Process.Start(info);
    }

    private static async Task<string?> StartWindowsServiceAsync(CancellationToken cancellationToken)
    {
        // 啟動服務需要管理員權限：通過 runas 觸發 UAC
        using var process = Process.Start(new ProcessStartInfo("sc.exe", $"start {WindowsServiceName}")
        {
            UseShellExecute = true,
            Verb = "runas",
            WindowStyle = ProcessWindowStyle.Hidden,
        });
        if (process is null)
        {
            return "sc.exe";
        }

        await process.WaitForExitAsync(cancellationToken);
        // 1056 = 服務已在運行
        return process.ExitCode is 0 or 1056 ? null : $"sc.exe exit code {process.ExitCode}";
    }

    private static bool IsWindowsServiceInstalled()
    {
        try
        {
            using var process = Process.Start(new ProcessStartInfo("sc.exe", $"query {WindowsServiceName}")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
            });
            if (process is null)
            {
                return false;
            }

            process.StandardOutput.ReadToEnd();
            process.WaitForExit(3000);
            // 1060 = 服務不存在
            return process.ExitCode != 1060;
        }
        catch (Win32Exception)
        {
            return false;
        }
    }
}
