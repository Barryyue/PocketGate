using System.Collections.Concurrent;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;

namespace NetworkControl.Engine.Fake;

/// <summary>虛擬應用的定義。</summary>
public sealed record FakeAppDefinition(
    string Key,
    string DisplayName,
    string Path,
    string? SignerId,
    string? Sha256 = null,
    string? UserId = null);

/// <summary>
/// 虛擬進程表：維護一組虛擬應用及其「運行中」的進程，同時充當進程信息、應用檢查、進程退出監聽的假實現。
/// </summary>
public sealed class FakeProcessTable : IProcessInfoProvider, IAppInspector, IProcessExitWatcher
{
    private readonly ConcurrentDictionary<string, FakeAppDefinition> _apps = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, ProcessSnapshot> _running = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<ProcessRef, List<Action>> _exitHandlers = new();
    private readonly TimeProvider _time;
    private int _nextPid = 10_000;

    public FakeProcessTable(TimeProvider time)
    {
        _time = time;
        foreach (var app in DefaultCatalog())
        {
            Register(app);
        }
    }

    public IReadOnlyCollection<FakeAppDefinition> Apps => _apps.Values.OrderBy(a => a.DisplayName, StringComparer.CurrentCulture).ToArray();

    public void Register(FakeAppDefinition app) => _apps[app.Key] = app;

    /// <summary>取得應用當前運行的進程；未運行時啟動一個。</summary>
    public ProcessSnapshot GetOrStart(string appKey)
    {
        var app = _apps.TryGetValue(appKey, out var a) ? a : throw new KeyNotFoundException($"未知的虛擬應用 {appKey}。");
        return _running.GetOrAdd(appKey, _ => new ProcessSnapshot(
            new ProcessRef(Interlocked.Increment(ref _nextPid), _time.GetUtcNow().UtcTicks),
            app.Path,
            app.UserId));
    }

    /// <summary>模擬應用退出（用於測試「直到應用退出」規則）。</summary>
    public bool Exit(string appKey)
    {
        if (!_running.TryRemove(appKey, out var snapshot))
        {
            return false;
        }

        if (_exitHandlers.TryRemove(snapshot.Process, out var handlers))
        {
            Action[] copy;
            lock (handlers)
            {
                copy = [.. handlers];
            }

            foreach (var handler in copy)
            {
                handler();
            }
        }

        return true;
    }

    /// <summary>模擬程序被替換（用於測試篡改檢測）。</summary>
    public void Tamper(string appKey, string? newSignerId, string? newSha256)
    {
        if (_apps.TryGetValue(appKey, out var app))
        {
            _apps[appKey] = app with { SignerId = newSignerId, Sha256 = newSha256 };
        }
    }

    public bool TryGetProcess(int pid, out ProcessSnapshot snapshot)
    {
        foreach (var s in _running.Values)
        {
            if (s.Process.Pid == pid)
            {
                snapshot = s;
                return true;
            }
        }

        snapshot = null!;
        return false;
    }

    public bool IsAlive(ProcessRef process) => _running.Values.Any(s => s.Process == process);

    public FileStamp? GetStamp(string path)
    {
        var app = FindByPath(path);
        // 用簽名者和哈希生成文件戳，模擬「程序被替換」時文件戳變化
        return app is null ? null : new FileStamp(app.Path.Length, HashCode.Combine(app.SignerId, app.Sha256));
    }

    public ValueTask<AppInspection> InspectAsync(string path, CancellationToken cancellationToken)
    {
        var app = FindByPath(path);
        var stamp = GetStamp(path) ?? new FileStamp(0, 0);
        var inspection = app is null
            ? new AppInspection(stamp, null, null, null)
            : new AppInspection(
                stamp,
                app.DisplayName,
                app.SignerId is null ? null : new CodeSignature(app.SignerId),
                app.SignerId is null ? app.Sha256 : null);
        return ValueTask.FromResult(inspection);
    }

    public IDisposable Watch(ProcessRef process, Action onExit)
    {
        var handlers = _exitHandlers.GetOrAdd(process, _ => []);
        lock (handlers)
        {
            handlers.Add(onExit);
        }

        return new Unsubscriber(() =>
        {
            lock (handlers)
            {
                handlers.Remove(onExit);
            }
        });
    }

    private FakeAppDefinition? FindByPath(string path) =>
        _apps.Values.FirstOrDefault(a => string.Equals(a.Path, path, StringComparison.OrdinalIgnoreCase));

    private static IEnumerable<FakeAppDefinition> DefaultCatalog()
    {
        var windows = OperatingSystem.IsWindows();
        string P(string win, string unix) => windows ? win : unix;

        yield return new("chrome", "Google Chrome",
            P(@"C:\Program Files\Google\Chrome\Application\chrome.exe", "/opt/google/chrome/chrome"), "Google LLC");
        yield return new("vscode", "Visual Studio Code",
            P(@"C:\Users\dev\AppData\Local\Programs\Microsoft VS Code\Code.exe", "/usr/share/code/code"), "Microsoft Corporation");
        yield return new("discord", "Discord",
            P(@"C:\Users\dev\AppData\Local\Discord\app-1.0.9187\Discord.exe", "/opt/discord/app-0.0.80/Discord"), "Discord Inc.");
        yield return new("curl", "curl",
            P(@"C:\Windows\System32\curl.exe", "/usr/bin/curl"), "Microsoft Windows");
        yield return new("python", "Python",
            P(@"C:\Python313\python.exe", "/usr/bin/python3"), null, "a3f5c1d2e4b6a7980112233445566778899aabbccddeeff00112233445566778");
        yield return new("game", "Some Game",
            P(@"D:\Games\SomeGame\game.exe", "/home/dev/Games/SomeGame/game"), null, "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0");
        yield return new("tool", "Unsigned Tool",
            P(@"C:\Users\dev\Downloads\tool.exe", "/home/dev/Downloads/tool"), null, "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
    }

    private sealed class Unsubscriber(Action dispose) : IDisposable
    {
        private Action? _dispose = dispose;

        public void Dispose() => Interlocked.Exchange(ref _dispose, null)?.Invoke();
    }
}
