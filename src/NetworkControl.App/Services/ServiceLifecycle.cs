using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using NetworkControl.Ipc;

namespace NetworkControl.App.Services;

/// <summary>可以從界面切換的引擎（與服務端 EngineNames 一致）。</summary>
public static class EngineKinds
{
    public const string Fake = "Fake";
    public const string Wfp = "Wfp";

    /// <summary>服務狀態中報告的引擎名稱（WfpEngine.Name 為 "WFP"）→ 配置值。</summary>
    public static string FromStatusName(string? name) =>
        string.Equals(name, "WFP", StringComparison.OrdinalIgnoreCase) ? Wfp : Fake;
}

/// <summary>
/// 開發環境下的服務生命週期：讓服務正常退出、用指定引擎重新啟動、退出界面時關閉開發服務。
/// 安裝版的系統服務由服務管理器負責，界面不會停止它。
/// </summary>
public sealed class ServiceLifecycle(ServiceConnection connection, IServiceLauncher launcher)
{
    private static readonly TimeSpan ShutdownTimeout = TimeSpan.FromSeconds(10);

    /// <summary>只有開發佈局才允許從界面切換引擎。</summary>
    public bool CanSwitchEngine => launcher.Kind == ServiceLaunchKind.Development;

    public ServiceLaunchKind LaunchKind => launcher.Kind;

    /// <summary>
    /// 用指定引擎重啟開發服務。新引擎啟動失敗（例如在 UAC 對話框中拒絕）時，恢復原來的引擎。
    /// 返回 null 表示成功，否則返回錯誤信息。
    /// </summary>
    public async Task<string?> RestartWithEngineAsync(string engine, string previousEngine, CancellationToken cancellationToken)
    {
        if (!CanSwitchEngine)
        {
            return "Not supported";
        }

        if (!await ShutdownAsync(cancellationToken))
        {
            return "The core service did not stop in time.";
        }

        var error = await launcher.StartAsync(engine, cancellationToken);
        if (error is not null && !string.Equals(engine, previousEngine, StringComparison.OrdinalIgnoreCase))
        {
            await launcher.StartAsync(previousEngine, cancellationToken);
        }

        await Task.Delay(TimeSpan.FromSeconds(1.5), cancellationToken);
        connection.RetryNow();
        return error;
    }

    /// <summary>退出界面前調用：開發佈局下一併關閉開發服務，避免殘留後台進程。</summary>
    public async Task ShutdownDevelopmentServiceAsync()
    {
        if (CanSwitchEngine)
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
            try
            {
                await ShutdownAsync(cts.Token);
            }
            catch (OperationCanceledException)
            {
                // 超時：不阻止界面退出
            }
        }
    }

    /// <summary>
    /// 完全退出時調用：無論開發服務還是系統服務都請求退出，攔截隨之停止。
    /// 系統服務正常退出不會觸發服務管理器的失敗重啟，下次開機時才會再次自動啟動。
    /// </summary>
    public async Task StopServiceAsync()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        try
        {
            await ShutdownAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            // 超時：不阻止界面退出
        }
    }

    /// <summary>請求服務退出，並等待它的 IPC 端點消失。</summary>
    private async Task<bool> ShutdownAsync(CancellationToken cancellationToken)
    {
        if (connection.Clients is { } clients)
        {
            try
            {
                await clients.Control.ShutdownAsync(new Empty(), cancellationToken: cancellationToken);
            }
            catch (RpcException)
            {
                // 連接可能在響應前就斷開了，繼續等待端點消失
            }
        }

        var deadline = DateTime.UtcNow + ShutdownTimeout;
        while (DateTime.UtcNow < deadline)
        {
            if (!IpcEndpoint.ResolveForClient().Any(e => e.MayBeAvailable))
            {
                return true;
            }

            await Task.Delay(200, cancellationToken);
        }

        return false;
    }
}
