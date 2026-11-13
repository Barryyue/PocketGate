using System.IO.Pipes;
using NetworkControl.App.Infrastructure;
using NetworkControl.Ipc;

namespace NetworkControl.App.Services;

/// <summary>
/// 每個用戶會話只允許運行一個 UI 實例。第二次啟動時通過本地命名管道通知已有實例顯示主窗口，然後退出。
/// </summary>
/// <remarks>
/// 管道只傳遞「顯示主窗口」這一個請求，不接受任何參數：其他本機進程最多只能讓窗口彈出來。
/// Windows 的命名管道是全機範圍的，名稱中帶上用戶名，避免多個登錄會話互相喚起。
/// </remarks>
internal sealed class SingleInstance : IDisposable
{
    private const string ActivateMessage = "activate";

    private readonly Mutex _mutex;
    private readonly CancellationTokenSource _stop = new();

    private SingleInstance(Mutex mutex) => _mutex = mutex;

    /// <summary>本進程持有的實例鎖（未持有時為 null）。</summary>
    public static SingleInstance? Current { get; private set; }

    private static string Scope => PortableLayout.IsActive ? "Portable" : "Installed";

    private static string PipeName => $"NetworkControl.App.{Environment.UserName}.{Scope}.activate";

    public static SingleInstance? TryAcquire()
    {
        // Windows 上 Local\ 前綴把互斥體限定在當前登錄會話；Unix 上 .NET 用共享內存文件實現命名互斥體。
        // 可攜版用單獨的名字，否則會與已安裝版互相搶佔。
        var name = OperatingSystem.IsWindows()
            ? PortableLayout.IsActive ? @"Local\NetworkControl.App.Portable" : @"Local\NetworkControl.App"
            : $"NetworkControl.App.{(PortableLayout.IsActive ? "Portable" : Environment.UserName)}";
        var mutex = new Mutex(initiallyOwned: true, name, out var createdNew);
        if (createdNew)
        {
            return Current = new SingleInstance(mutex);
        }

        mutex.Dispose();
        return null;
    }

    /// <summary>通知已在運行的實例顯示主窗口。返回是否送達。</summary>
    public static bool SignalExisting(TimeSpan timeout)
    {
        try
        {
            using var client = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
            client.Connect(timeout);
            using var writer = new StreamWriter(client);
            writer.WriteLine(ActivateMessage);
            return true;
        }
        catch (Exception ex) when (ex is TimeoutException or IOException or UnauthorizedAccessException)
        {
            AppLog.Warn($"Could not reach the running instance: {ex.Message}");
            return false;
        }
    }

    /// <summary>開始監聽第二個實例的喚起請求；<paramref name="onActivate"/> 在後台線程上調用。</summary>
    public void Listen(Action onActivate) => _ = ListenAsync(onActivate, _stop.Token);

    public void Dispose()
    {
        _stop.Cancel();
        _stop.Dispose();
        _mutex.ReleaseMutex();
        _mutex.Dispose();
        if (ReferenceEquals(Current, this))
        {
            Current = null;
        }
    }

    private static async Task ListenAsync(Action onActivate, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await using var server = new NamedPipeServerStream(PipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(cancellationToken);
                using var reader = new StreamReader(server);
                var line = await reader.ReadLineAsync(cancellationToken);
                if (line == ActivateMessage)
                {
                    onActivate();
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // 管道被佔用或客戶端異常斷開：稍後重試，不影響界面
                AppLog.Warn($"Single-instance pipe error: {ex.Message}");
                await Task.Delay(TimeSpan.FromSeconds(1), cancellationToken).ConfigureAwait(false);
            }
        }
    }
}
