using System.ComponentModel;
using System.Diagnostics;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;

namespace NetworkControl.Engine.Windows.Platform;

/// <summary>Windows 進程信息。服務以 SYSTEM 運行，可以讀取所有進程的映像路徑。</summary>
internal sealed class WindowsProcessInfoProvider : IProcessInfoProvider
{
    public bool TryGetProcess(int pid, out ProcessSnapshot snapshot)
    {
        snapshot = null!;
        try
        {
            using var process = Process.GetProcessById(pid);
            var path = process.MainModule?.FileName;
            if (string.IsNullOrEmpty(path))
            {
                return false;
            }

            snapshot = new ProcessSnapshot(new ProcessRef(pid, process.StartTime.ToUniversalTime().Ticks), path);
            return true;
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException or Win32Exception)
        {
            // 進程已退出或無權訪問
            return false;
        }
    }

    public bool IsAlive(ProcessRef process)
    {
        try
        {
            using var p = Process.GetProcessById(process.Pid);
            return !p.HasExited && p.StartTime.ToUniversalTime().Ticks == process.StartTimeTicks;
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException or Win32Exception)
        {
            return false;
        }
    }
}

/// <summary>監聽進程退出（「直到應用退出」規則）。</summary>
internal sealed class WindowsProcessExitWatcher(WindowsProcessInfoProvider processes) : IProcessExitWatcher
{
    public IDisposable Watch(ProcessRef process, Action onExit)
    {
        Process p;
        try
        {
            p = Process.GetProcessById(process.Pid);
            if (!processes.IsAlive(process))
            {
                p.Dispose();
                onExit();
                return new Subscription(null, null);
            }

            p.EnableRaisingEvents = true;
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException or Win32Exception)
        {
            onExit();
            return new Subscription(null, null);
        }

        EventHandler handler = (_, _) => onExit();
        p.Exited += handler;
        return new Subscription(p, handler);
    }

    private sealed class Subscription(Process? process, EventHandler? handler) : IDisposable
    {
        public void Dispose()
        {
            if (process is not null)
            {
                process.Exited -= handler;
                process.Dispose();
            }
        }
    }
}
