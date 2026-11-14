using System.ComponentModel;
using System.Diagnostics;
using System.Net.Http;
using System.Reflection;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using NetworkControl.Ipc.Updates;

namespace NetworkControl.App.Services;

/// <summary>
/// 檢查更新：只在界面進程中讀取發布方的 JSON 清單（<c>latest.json</c>），不改動服務、不上傳任何內容。
/// 清單地址由 <c>Directory.Build.props</c> 的 <c>UpdateFeedUrl</c> 在構建時寫入程序集；
/// 地址為空、Debug 構建或版本帶 <c>-dirty</c> 時整個功能隱藏。
/// </summary>
public sealed partial class UpdateChecker : ObservableObject, IAsyncDisposable
{
    /// <summary>程序集元數據中更新清單地址的鍵（見 NetworkControl.App.csproj）。</summary>
    public const string FeedUrlMetadataKey = "UpdateFeedUrl";

    private static readonly TimeSpan InitialDelay = TimeSpan.FromSeconds(30);

    private static readonly TimeSpan Interval = TimeSpan.FromHours(24);

    private readonly PreferencesStore _preferences;
    private readonly LocalizationService _localization;
    private readonly HttpClient _http;
    private readonly CancellationTokenSource _shutdown = new();
    private readonly Uri? _feed;
    private Task? _loop;
    private int _checking;
    private bool _initializing = true;
    private bool _disposed;

    public UpdateChecker(PreferencesStore preferences, LocalizationService localization)
    {
        _preferences = preferences;
        _localization = localization;

        // 檢查更新是公網請求，必須尊重系統代理（與本地 IPC 相反，IPC 顯式關閉代理）；
        // 不帶任何用戶信息，User-Agent 只有程序名和版本。
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
        _http.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", $"NetworkControl/{CurrentVersion}");

        _feed = ResolveFeedUri();
        AutoCheckEnabled = preferences.Current.AutoCheckUpdates ?? true;
        _initializing = false;

        _localization.LanguageChanged += OnLanguageChanged;
    }

    /// <summary>清單地址已配置且這是可發布的版本（不是 Debug / dirty 構建）。</summary>
    public bool IsSupported => _feed is not null && UpdateVersion.TryParse(AppLog.Version, out _);

    /// <summary>目前版本，去掉 <c>+提交哈希</c> 後綴。</summary>
    public string CurrentVersion => UpdateVersion.TryParse(AppLog.Version, out var version) ? version.ToString() : AppLog.Version;

    /// <summary>自動檢查更新（默認開啟）；切換後立即保存到界面偏好。</summary>
    [ObservableProperty]
    public partial bool AutoCheckEnabled { get; set; }

    /// <summary>正在讀取清單。界面用它顯示進度並避免重複點擊。</summary>
    public bool IsChecking => _checking != 0;

    /// <summary>最近一次成功讀取清單的時間；null 表示本次會話還沒有檢查過。</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(StatusText))]
    public partial DateTimeOffset? LastCheckedAt { get; set; }

    /// <summary>可提示的新版本；null 表示沒有新版、已是最新、或者用戶忽略了該版本。</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasUpdate))]
    [NotifyPropertyChangedFor(nameof(AvailableVersion))]
    [NotifyPropertyChangedFor(nameof(AvailableText))]
    [NotifyPropertyChangedFor(nameof(NotesText))]
    [NotifyPropertyChangedFor(nameof(HasNotes))]
    public partial UpdateManifest? Available { get; set; }

    public bool HasUpdate => Available is not null;

    /// <summary>提示中的版本號（去掉 <c>+提交哈希</c>）；沒有新版時為空字串。</summary>
    public string AvailableVersion => Available is null
        ? ""
        : UpdateVersion.TryParse(Available.Version, out var version) ? version.ToString() : Available.Version;

    public bool HasNotes => !string.IsNullOrWhiteSpace(NotesText);

    /// <summary>例如「有新版本 0.4.0 可以使用（目前為 0.3.0）。」</summary>
    public string AvailableText => Available is null
        ? ""
        : _localization.Format(
            SettingsLangResourceKind.UpdateAvailable,
            UpdateVersion.TryParse(Available.Version, out var version) ? version.ToString() : Available.Version,
            CurrentVersion);

    /// <summary>按當前界面語言選擇更新說明；找不到對應語言時依次退回 zh-TW / zh-CN / en-US。</summary>
    public string NotesText
    {
        get
        {
            if (Available?.Notes is not { Count: > 0 } notes)
            {
                return "";
            }

            var preference = _localization.Preference;
            if (preference is not null && notes.TryGetValue(preference, out var preferred) && !string.IsNullOrWhiteSpace(preferred))
            {
                return preferred;
            }

            foreach (var language in (ReadOnlySpan<string>)["zh-TW", "zh-CN", "en-US"])
            {
                if (notes.TryGetValue(language, out var text) && !string.IsNullOrWhiteSpace(text))
                {
                    return text;
                }
            }

            return notes.Values.FirstOrDefault(v => !string.IsNullOrWhiteSpace(v)) ?? "";
        }
    }

    /// <summary>例如「上次檢查：2026-11-14 14:30」。</summary>
    public string StatusText => IsChecking
        ? _localization.Get(SettingsLangResourceKind.UpdateChecking)
        : LastCheckedAt is { } time
            ? _localization.Format(SettingsLangResourceKind.UpdateLastChecked, time.ToLocalTime().ToString("g", _localization.Culture))
            : _localization.Get(SettingsLangResourceKind.UpdateNeverChecked);

    /// <summary>啟動自動檢查：約 30 秒後第一次，之後每 24 小時一次。未配置清單或開發構建時不啟動。</summary>
    public void Start()
    {
        if (!IsSupported)
        {
            AppLog.Info("Update check is disabled (no update feed URL, debug build, or dirty build).");
            return;
        }

        _loop ??= Task.Run(AutomaticLoopAsync);
    }

    /// <summary>讀取清單並更新 <see cref="Available"/>；任何失敗只寫界面日誌，不向用戶拋出。</summary>
    public async Task CheckAsync(CancellationToken cancellationToken = default)
    {
        if (!IsSupported || _feed is null)
        {
            return;
        }

        if (Interlocked.Exchange(ref _checking, 1) != 0)
        {
            return;
        }

        await Dispatcher.UIThread.InvokeAsync(() =>
        {
            OnPropertyChanged(nameof(IsChecking));
            OnPropertyChanged(nameof(StatusText));
        });

        try
        {
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _shutdown.Token);
            using var response = await _http.GetAsync(_feed, HttpCompletionOption.ResponseContentRead, linked.Token);
            response.EnsureSuccessStatusCode();
            var json = await response.Content.ReadAsStringAsync(linked.Token);
            if (!UpdateManifestParser.TryParse(json, out var manifest, out var error))
            {
                AppLog.Info($"Update check failed: {error}");
                return;
            }

            var available = UpdateSelection.IsUpdateAvailable(manifest, AppLog.Version, _preferences.Current.IgnoredUpdateVersion)
                ? manifest
                : null;
            if (available is not null && !UpdateSelection.IsSupportedOn(available, Environment.OSVersion.Version))
            {
                AppLog.Info($"Update {available.Version} requires a newer version of Windows.");
                available = null;
            }

            AppLog.Info(available is null
                ? $"Update check completed; the latest version is {manifest.Version}."
                : $"Update {manifest.Version} is available.");

            await Dispatcher.UIThread.InvokeAsync(() =>
            {
                LastCheckedAt = DateTimeOffset.Now;
                Available = available;
            });
        }
        catch (Exception ex) when (ex is HttpRequestException or OperationCanceledException or FormatException or IOException or InvalidOperationException)
        {
            // 網絡不可用、超時、清單損壞都只記日誌：後台的檢查不應打斷用戶
            AppLog.Info($"Update check failed: {ex.Message}");
        }
        finally
        {
            Interlocked.Exchange(ref _checking, 0);
            await Dispatcher.UIThread.InvokeAsync(() =>
            {
                OnPropertyChanged(nameof(IsChecking));
                OnPropertyChanged(nameof(StatusText));
            });
        }
    }

    /// <summary>忽略目前提示的版本，之後不再提示（有新版本時仍會提示）。</summary>
    public void IgnoreAvailable()
    {
        if (Available is not { } manifest)
        {
            return;
        }

        _preferences.Update(p => p with { IgnoredUpdateVersion = manifest.Version });
        Available = null;
    }

    /// <summary>用系統默認瀏覽器打開下載頁面；地址已在解析清單時校驗為 http / https。</summary>
    public void OpenDownloadPage()
    {
        if (Available is not { } manifest || !UpdateManifestParser.TryCreateWebUri(manifest.Url, out var uri))
        {
            return;
        }

        try
        {
            Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
        }
        catch (Exception ex) when (ex is Win32Exception or InvalidOperationException)
        {
            AppLog.Info($"Could not open the download page: {ex.Message}");
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _localization.LanguageChanged -= OnLanguageChanged;
        await _shutdown.CancelAsync();
        if (_loop is not null)
        {
            try
            {
                await _loop;
            }
            catch (OperationCanceledException)
            {
                // 應用退出
            }
        }

        _http.Dispose();
        _shutdown.Dispose();
    }

    partial void OnAutoCheckEnabledChanged(bool value)
    {
        if (_initializing)
        {
            return;
        }

        _preferences.Update(p => p with { AutoCheckUpdates = value });
        if (value)
        {
            // 用戶剛打開開關時不用等下一輪 24 小時
            _ = CheckAsync();
        }
    }

    private static Uri? ResolveFeedUri()
    {
        var feedUrl = typeof(UpdateChecker).Assembly
            .GetCustomAttributes<AssemblyMetadataAttribute>()
            .FirstOrDefault(attribute => attribute.Key == FeedUrlMetadataKey)?.Value;

#if DEBUG
        // 開發構建不向正式清單發請求；IsSupported 因此為 false，設定頁不顯示更新卡片
        return null;
#else
        if (AppLog.Version.Contains("-dirty", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        return UpdateManifestParser.TryCreateWebUri(feedUrl, out var uri) ? uri : null;
#endif
    }

    private async Task AutomaticLoopAsync()
    {
        try
        {
            await Task.Delay(InitialDelay, _shutdown.Token);
            while (!_shutdown.IsCancellationRequested)
            {
                if (AutoCheckEnabled)
                {
                    await CheckAsync(_shutdown.Token);
                }

                await Task.Delay(Interval, _shutdown.Token);
            }
        }
        catch (OperationCanceledException) when (_shutdown.IsCancellationRequested)
        {
            // 應用退出
        }
    }

    private void OnLanguageChanged(object? sender, EventArgs e) => OnPropertyChanged(string.Empty);
}
