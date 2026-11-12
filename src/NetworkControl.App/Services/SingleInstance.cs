using NetworkControl.Ipc;

namespace NetworkControl.App.Services;

/// <summary>每個用戶會話只允許運行一個 UI 實例。</summary>
internal sealed class SingleInstance : IDisposable
{
    private readonly Mutex _mutex;

    private SingleInstance(Mutex mutex) => _mutex = mutex;

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
            return new SingleInstance(mutex);
        }

        mutex.Dispose();
        return null;
    }

    public void Dispose()
    {
        _mutex.ReleaseMutex();
        _mutex.Dispose();
    }
}
