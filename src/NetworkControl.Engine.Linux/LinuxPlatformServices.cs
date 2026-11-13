using System.Security.Cryptography;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;

namespace NetworkControl.Engine.Linux;

/// <summary>
/// Linux 可執行文件檢查：Linux 沒有統一的代碼簽名，應用按 SHA-256 綁定。
/// 後續可以接入包管理器（dpkg / rpm）把系統目錄中的文件標記為平台程序。
/// </summary>
internal sealed class LinuxAppInspector : IAppInspector
{
    public FileStamp? GetStamp(string path)
    {
        try
        {
            var info = new FileInfo(path);
            return info.Exists ? new FileStamp(info.Length, info.LastWriteTimeUtc.Ticks) : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            return null;
        }
    }

    public async ValueTask<AppInspection> InspectAsync(string path, CancellationToken cancellationToken)
    {
        var stamp = GetStamp(path) ?? new FileStamp(0, 0);
        if (stamp.Size == 0)
        {
            return new AppInspection(stamp, null, null, null);
        }

        try
        {
            await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1 << 16, useAsync: true);
            var hash = Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, cancellationToken));
            return new AppInspection(stamp, null, null, hash);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return new AppInspection(stamp, null, null, null);
        }
    }
}

/// <summary>
/// 監聽進程退出。非子進程在 Linux 上收不到退出通知（.NET 的 Process.Exited 依賴 waitpid），
/// 這裡按固定間隔檢查 /proc 中的啟動時間，PID 被複用也能識別。
/// </summary>
internal sealed class LinuxProcessExitWatcher(IProcessInfoProvider processes, TimeProvider time) : IProcessExitWatcher
{
    internal static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(2);

    public IDisposable Watch(ProcessRef process, Action onExit)
    {
        ArgumentNullException.ThrowIfNull(onExit);
        var subscription = new Subscription(processes, process, onExit);
        if (!processes.IsAlive(process))
        {
            subscription.Fire();
            return subscription;
        }

        subscription.Start(time, PollInterval);
        return subscription;
    }

    private sealed class Subscription(IProcessInfoProvider processes, ProcessRef process, Action onExit) : IDisposable
    {
        private ITimer? _timer;
        private int _done;

        public void Start(TimeProvider time, TimeSpan interval) =>
            _timer = time.CreateTimer(_ => Check(), null, interval, interval);

        public void Fire()
        {
            if (Interlocked.Exchange(ref _done, 1) == 0)
            {
                _timer?.Dispose();
                onExit();
            }
        }

        public void Dispose()
        {
            Interlocked.Exchange(ref _done, 1);
            _timer?.Dispose();
        }

        private void Check()
        {
            if (Volatile.Read(ref _done) == 0 && !processes.IsAlive(process))
            {
                Fire();
            }
        }
    }
}
