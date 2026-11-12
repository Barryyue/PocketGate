using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Apps;

/// <summary>進程快照。</summary>
public sealed record ProcessSnapshot(
    ProcessRef Process,
    string ExecutablePath,
    string? UserId = null,
    int? ParentPid = null,
    string? CommandLine = null,
    string? ServiceName = null,
    string? PackageId = null);

/// <summary>文件戳：用來低成本判斷可執行文件是否變化。</summary>
public readonly record struct FileStamp(long Size, long LastWriteTicks);

/// <summary>對可執行文件的一次檢查結果。</summary>
public sealed record AppInspection(FileStamp Stamp, string? DisplayName, CodeSignature? Signature, string? Sha256);

/// <summary>【平台實現】按 PID 查詢進程信息。</summary>
public interface IProcessInfoProvider
{
    bool TryGetProcess(int pid, out ProcessSnapshot snapshot);

    /// <summary>判斷進程是否仍然存活（PID 與啟動時間都一致）。</summary>
    bool IsAlive(ProcessRef process);
}

/// <summary>【平台實現】檢查可執行文件：名稱、簽名、哈希。可能較慢，結果會被緩存。</summary>
public interface IAppInspector
{
    /// <summary>低成本地讀取文件戳；文件不存在時返回 null。</summary>
    FileStamp? GetStamp(string path);

    ValueTask<AppInspection> InspectAsync(string path, CancellationToken cancellationToken);
}

/// <summary>【平台實現】監聽進程退出，用於「直到應用退出」規則。</summary>
public interface IProcessExitWatcher
{
    IDisposable Watch(ProcessRef process, Action onExit);
}

/// <summary>【存儲實現】應用信息的持久化。</summary>
public interface IAppRepository
{
    /// <summary>按應用鍵查找或創建應用記錄，並用最新的檢查結果更新它。</summary>
    ValueTask<AppIdentity> UpsertAsync(AppKey key, AppInspection inspection, CancellationToken cancellationToken);

    ValueTask<AppIdentity?> GetAsync(long appId, CancellationToken cancellationToken);
}

/// <summary>把連接解析為應用身份。</summary>
public interface IAppIdentityResolver
{
    ValueTask<AppIdentity?> ResolveAsync(FlowInfo flow, CancellationToken cancellationToken);
}
