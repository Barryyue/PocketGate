using System.Collections.Concurrent;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Apps;

/// <summary>
/// 帶兩級緩存的應用身份解析器：
/// 1. 進程級緩存（ProcessRef → AppIdentity），熱路徑上零 I/O；
/// 2. 文件級緩存（AppKey → 文件戳 + AppIdentity），文件變化時重新檢查。
/// </summary>
public sealed partial class AppIdentityResolver(
    IProcessInfoProvider processes,
    IAppInspector inspector,
    IAppRepository repository,
    ILogger<AppIdentityResolver> logger) : IAppIdentityResolver
{
    private const int MaxProcessCacheEntries = 16_384;

    private readonly ConcurrentDictionary<ProcessRef, AppIdentity> _byProcess = new();
    private readonly ConcurrentDictionary<AppKey, Lazy<Task<CachedApp>>> _byKey = new();

    public async ValueTask<AppIdentity?> ResolveAsync(FlowInfo flow, CancellationToken cancellationToken)
    {
        if (flow.Process is { } process && _byProcess.TryGetValue(process, out var cached))
        {
            return cached;
        }

        ProcessSnapshot? snapshot = null;
        if (flow.Process is { } p && processes.TryGetProcess(p.Pid, out var s) && s.Process == p)
        {
            snapshot = s;
        }

        var path = snapshot?.ExecutablePath ?? flow.ExecutablePath;
        if (string.IsNullOrEmpty(path))
        {
            return null;
        }

        var key = new AppKey(path, snapshot?.ServiceName, snapshot?.PackageId);
        AppIdentity identity;
        try
        {
            identity = await GetOrInspectAsync(key, cancellationToken);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            LogInspectionFailed(logger, ex, path);
            return null;
        }

        if (snapshot?.CommandLine is { } commandLine)
        {
            identity = identity with { CommandLine = commandLine };
        }

        if (flow.Process is { } proc)
        {
            if (_byProcess.Count >= MaxProcessCacheEntries)
            {
                _byProcess.Clear();
            }

            _byProcess[proc] = identity;
        }

        return identity;
    }

    /// <summary>進程退出時調用，釋放進程級緩存。</summary>
    public void Forget(ProcessRef process) => _byProcess.TryRemove(process, out _);

    private async Task<AppIdentity> GetOrInspectAsync(AppKey key, CancellationToken cancellationToken)
    {
        var currentStamp = inspector.GetStamp(key.Path);

        while (true)
        {
            var lazy = _byKey.GetOrAdd(key, k => new Lazy<Task<CachedApp>>(() => InspectAndStoreAsync(k, CancellationToken.None)));
            CachedApp result;
            try
            {
                result = await lazy.Value.WaitAsync(cancellationToken);
            }
            catch (Exception) when (!cancellationToken.IsCancellationRequested)
            {
                // 失敗的結果不要留在緩存裡，下次重試
                _byKey.TryRemove(new KeyValuePair<AppKey, Lazy<Task<CachedApp>>>(key, lazy));
                throw;
            }

            if (currentStamp is null || result.Stamp == currentStamp)
            {
                return result.Identity;
            }

            // 文件已變化（升級或被替換）：丟棄舊緩存，重新檢查
            LogFileChanged(logger, key.Path);
            _byKey.TryRemove(new KeyValuePair<AppKey, Lazy<Task<CachedApp>>>(key, lazy));
        }
    }

    private async Task<CachedApp> InspectAndStoreAsync(AppKey key, CancellationToken cancellationToken)
    {
        var inspection = await inspector.InspectAsync(key.Path, cancellationToken);
        var identity = await repository.UpsertAsync(key, inspection, cancellationToken);
        return new CachedApp(inspection.Stamp, identity);
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "檢查應用 {Path} 失敗")]
    private static partial void LogInspectionFailed(ILogger logger, Exception exception, string path);

    [LoggerMessage(Level = LogLevel.Information, Message = "應用文件 {Path} 已變化，重新檢查")]
    private static partial void LogFileChanged(ILogger logger, string path);

    private sealed record CachedApp(FileStamp Stamp, AppIdentity Identity);
}
