namespace NetworkControl.Engine.Vpn.Update;

public sealed record AppUpdateState(
    bool Checking = false,
    UpdateInfo? Info = null,
    bool Downloading = false,
    int Progress = 0,
    string? DownloadedPath = null,
    string? Error = null,
    bool ShowAvailablePrompt = false,
    bool ShowReadyPrompt = false);

/// <summary>
/// 應用級更新管理器：進入應用時自動檢查（10 分鐘冷卻、遵守純離線模式），背景下載切換分頁不中斷，完成後彈窗提醒安裝。
/// </summary>
public sealed class AppUpdateManager
{
    public static readonly TimeSpan AutoCheckCooldown = TimeSpan.FromMinutes(10);

    private readonly string _updatesDir;
    private readonly string _currentVersion;
    private readonly Func<bool> _offlineMode;
    private readonly Func<CancellationToken, Task<UpdateInfo>> _checkUpdate;
    private readonly Func<string, string, string?, Action<int>, CancellationToken, Task<string>> _downloadApk;
    private readonly Func<DateTimeOffset> _now;
    private readonly object _gate = new();

    private DateTimeOffset? _lastAutoCheck;
    private string? _dismissedVersion;
    private AppUpdateState _state = new();

    public event Action<AppUpdateState>? StateChanged;

    public AppUpdateState State
    {
        get { lock (_gate) return _state; }
    }

    public AppUpdateManager(
        string updatesDir,
        string currentVersion,
        Func<bool> offlineMode,
        Func<CancellationToken, Task<UpdateInfo>>? checkUpdate = null,
        Func<string, string, string?, Action<int>, CancellationToken, Task<string>>? downloadApk = null,
        Func<DateTimeOffset>? now = null)
    {
        _updatesDir = updatesDir;
        _currentVersion = currentVersion;
        _offlineMode = offlineMode;
        _checkUpdate = checkUpdate ?? (ct => AppUpdateChecker.CheckAutoAsync(currentVersion, ct: ct));
        _downloadApk = downloadApk ?? ((url, target, fallback, progress, ct) =>
            AppUpdateChecker.DownloadApkAsync(url, target, fallback, currentVersion, progress, ct));
        _now = now ?? (() => DateTimeOffset.UtcNow);
    }

    public void CheckOnEnter()
    {
        if (_offlineMode()) return;
        lock (_gate)
        {
            if (_state.Checking || _state.Downloading || _state.DownloadedPath is not null) return;
            var now = _now();
            if (_lastAutoCheck is { } prev && now - prev < AutoCheckCooldown) return;
            _lastAutoCheck = now;
        }

        _ = CheckAsync(manual: false);
    }

    public Task<UpdateInfo?> CheckManuallyAsync(CancellationToken ct = default)
    {
        if (_offlineMode()) return Task.FromResult<UpdateInfo?>(null);
        lock (_gate)
        {
            if (_state.Checking) return Task.FromResult<UpdateInfo?>(null);
            _lastAutoCheck = _now();
        }

        return CheckAsync(manual: true, ct);
    }

    private async Task<UpdateInfo?> CheckAsync(bool manual, CancellationToken ct = default)
    {
        UpdateState(s => s with { Checking = true, Error = null });
        UpdateInfo info;
        try
        {
            info = await _checkUpdate(ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            info = new UpdateInfo(null, null, ex.Message, _currentVersion);
        }

        UpdateState(prev =>
        {
            var hasNew = info.HasUpdate;
            var shouldPrompt = hasNew && (manual || !string.Equals(info.LatestVersion, _dismissedVersion, StringComparison.Ordinal));
            var readyPath = prev.DownloadedPath is { } p && File.Exists(p) && prev.Info?.LatestVersion == info.LatestVersion
                ? p
                : null;
            return prev with
            {
                Checking = false,
                Info = info,
                DownloadedPath = readyPath,
                Error = manual ? info.Error : prev.Error,
                ShowAvailablePrompt = readyPath is null && shouldPrompt,
            };
        });

        return info;
    }

    public void StartDownload(UpdateInfo? info = null)
    {
        if (_offlineMode()) return;
        UpdateInfo? target;
        lock (_gate)
        {
            if (_state.Downloading) return;
            target = info ?? _state.Info;
        }

        if (target is null || !target.HasUpdate || string.IsNullOrWhiteSpace(target.DownloadUrl)) return;

        var safeTag = string.Concat((target.LatestVersion ?? "latest").Select(c => char.IsLetterOrDigit(c) || c is '.' or '_' or '-' ? c : '_'));
        var targetFile = Path.Combine(_updatesDir, $"PocketGate-{safeTag}-AtomUI.apk");
        if (IsValidApk(targetFile))
        {
            UpdateState(s => s with
            {
                Info = target,
                Downloading = false,
                Progress = 100,
                DownloadedPath = targetFile,
                Error = null,
                ShowAvailablePrompt = false,
                ShowReadyPrompt = true,
            });
            return;
        }

        UpdateState(s => s with
        {
            Info = target,
            Downloading = true,
            Progress = 0,
            DownloadedPath = null,
            Error = null,
            ShowAvailablePrompt = false,
            ShowReadyPrompt = false,
        });

        _ = Task.Run(async () =>
        {
            try
            {
                var path = await _downloadApk(
                    target.DownloadUrl!,
                    targetFile,
                    target.FallbackDownloadUrl,
                    percent => UpdateState(s => s.Downloading ? s with { Progress = Math.Clamp(percent, 0, 100) } : s),
                    CancellationToken.None).ConfigureAwait(false);

                UpdateState(s => s with
                {
                    Downloading = false,
                    Progress = 100,
                    DownloadedPath = path,
                    Error = null,
                    ShowAvailablePrompt = false,
                    ShowReadyPrompt = true,
                });
            }
            catch (Exception ex)
            {
                UpdateState(s => s with
                {
                    Downloading = false,
                    Progress = 0,
                    Error = ex.Message,
                });
            }
        });
    }

    public void DismissAvailablePrompt()
    {
        UpdateState(s =>
        {
            if (!string.IsNullOrWhiteSpace(s.Info?.LatestVersion))
            {
                _dismissedVersion = s.Info.LatestVersion;
            }

            return s with { ShowAvailablePrompt = false };
        });
    }

    public void DismissReadyPrompt() => UpdateState(s => s with { ShowReadyPrompt = false });

    private void UpdateState(Func<AppUpdateState, AppUpdateState> mutator)
    {
        AppUpdateState snapshot;
        lock (_gate)
        {
            _state = mutator(_state);
            snapshot = _state;
        }

        StateChanged?.Invoke(snapshot);
    }

    private static bool IsValidApk(string path)
    {
        try
        {
            if (!File.Exists(path) || new FileInfo(path).Length < 1024) return false;
            using var fs = File.OpenRead(path);
            Span<byte> magic = stackalloc byte[4];
            return fs.Read(magic) == 4 && magic[0] == 0x50 && magic[1] == 0x4B && magic[2] == 0x03 && magic[3] == 0x04;
        }
        catch
        {
            return false;
        }
    }
}
