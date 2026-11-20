using System.Collections.ObjectModel;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Localization;
using NetworkControl.Android.Platform;
using NetworkControl.Core.Domain;
using NetworkControl.Engine.Vpn.Update;

namespace NetworkControl.Android.ViewModels;

/// <summary>總覽頁：保護開關、模式、一鍵斷網、今日阻擋、待決詢問。</summary>
/// <summary>今日用量排行中的一行。</summary>
public sealed record UsageRow(string Label, string Bytes, double Percent = 0);

/// <summary>總覽頁配置文件下拉中的一行。</summary>
public sealed record ProfileChoice(Guid Id, string Name, bool IsActive)
{
    /// <summary>AtomUI ComboBox 的選中項直接顯示 ToString()。</summary>
    public override string ToString() => Name;
}

public sealed partial class OverviewViewModel : LocalizedViewModel
{
    private static readonly FilterMode[] SelectableModes = [FilterMode.Ask, FilterMode.Whitelist, FilterMode.Blacklist];

    private readonly Action _showPrompt;
    private readonly Action _openPermissionGuide;
    private readonly DispatcherTimer _speedTimer;
    private long _lastBytesIn = -1;
    private long _lastBytesOut = -1;
    private bool _syncing;
    private bool _syncingProfile;

    public OverviewViewModel(UiContext context, Action showPrompt, Action openPermissionGuide)
        : base(context)
    {
        _showPrompt = showPrompt;
        _openPermissionGuide = openPermissionGuide;
        for (var i = 0; i < 36; i++)
        {
            _downSamples.Add(0L);
            _upSamples.Add(0L);
        }

        _speedTimer = new DispatcherTimer(TimeSpan.FromSeconds(1), DispatcherPriority.Background, (_, _) => SampleSpeed());
        _speedTimer.Start();
        Refresh();
    }

    /// <summary>保護開關（雙向綁定）。用戶操作時觸發授權流程。</summary>
    [ObservableProperty]
    public partial bool IsProtectionOn { get; set; }

    [ObservableProperty]
    public partial bool IsBusy { get; set; }

    /// <summary>分段選擇器的索引；-1 表示當前模式不在可選列表中（全部封鎖、停用）。</summary>
    [ObservableProperty]
    public partial int ModeIndex { get; set; } = -1;

    [ObservableProperty]
    public partial long BlockedToday { get; set; }

    [ObservableProperty]
    public partial bool NotificationsOff { get; set; }

    public ProtectionStatus Status { get; private set; }

    public FilterMode Mode { get; private set; }

    public int PendingPrompts { get; private set; }

    public bool HasPendingPrompts => PendingPrompts > 0;

    public bool IsBlockAll => Mode == FilterMode.BlockAll;

    /// <summary>配置文件；只有一個時隱藏選擇器。</summary>
    public System.Collections.ObjectModel.ObservableCollection<ProfileChoice> Profiles { get; } = [];

    public bool ShowProfiles => Profiles.Count > 1;

    [ObservableProperty]
    public partial ProfileChoice? SelectedProfile { get; set; }

    /// <summary>切換配置文件失敗時的提示（R6）；下一次成功操作或刷新時清除。</summary>
    [ObservableProperty]
    public partial string? ProfileError { get; set; }

    public bool HasProfileError => !string.IsNullOrEmpty(ProfileError);

    partial void OnProfileErrorChanged(string? value) => OnPropertyChanged(nameof(HasProfileError));

    public string StatusText => Loc.Get(Status switch
    {
        ProtectionStatus.On => OverviewLangResourceKind.StatusOn,
        ProtectionStatus.Starting => OverviewLangResourceKind.StatusStarting,
        ProtectionStatus.NeedsConsent => OverviewLangResourceKind.StatusNeedsConsent,
        ProtectionStatus.Revoked => OverviewLangResourceKind.StatusRevoked,
        ProtectionStatus.Failed => OverviewLangResourceKind.StatusFailed,
        _ => OverviewLangResourceKind.StatusOff,
    });

    public string StatusHint => Status switch
    {
        ProtectionStatus.On or ProtectionStatus.Starting => Loc.Get(OverviewLangResourceKind.HintOn),
        ProtectionStatus.NeedsConsent => Loc.Get(OverviewLangResourceKind.HintNeedsConsent),
        ProtectionStatus.Revoked => Loc.Get(OverviewLangResourceKind.HintRevoked),
        ProtectionStatus.Failed => Backend.ProtectionError ?? "",
        _ => Loc.Get(OverviewLangResourceKind.HintOff),
    };

    /// <summary>狀態卡片的強調色（AtomUI 預設色名）。</summary>
    public string StatusColor => Status switch
    {
        ProtectionStatus.On => "success",
        ProtectionStatus.Off => "default",
        ProtectionStatus.Starting => "processing",
        ProtectionStatus.NeedsConsent => "warning",
        _ => "error",
    };

    public string ExitBadgeText => Backend.Preferences.ForwardingEnabled
        ? (Backend.Preferences.ForwardingUpstream == "socks5"
            ? $"{Loc.Get(SettingsLangResourceKind.ForwardingMode)} · SOCKS5"
            : $"{Loc.Get(SettingsLangResourceKind.ForwardingMode)} · {Loc.Get(SettingsLangResourceKind.ForwardingDirect)}")
        : (Backend.ActiveProfile.Name);

    public bool IsProtectionActive => Status is ProtectionStatus.On or ProtectionStatus.Starting;

    public string ModeDescriptionText => ModeDescription(Mode);

    public string SkippedRulesText => Loc.Format(OverviewLangResourceKind.SkippedRules, SkippedRules);

    public int SkippedRules { get; private set; }

    public bool HasSkippedRules => SkippedRules > 0;

    public bool HasQuietPeriod => Backend.LastQuietPeriod is not null;

    public string QuietPeriodText => Backend.LastQuietPeriod is { } summary
        ? Loc.Format(OverviewLangResourceKind.QuietPeriod, summary.Apps.Count, summary.Denied)
        : "";

    /// <summary>今日用量（FR-STA-01）；沒有「使用情況存取權」時顯示授權入口。</summary>
    [ObservableProperty]
    public partial bool HasTrafficAccess { get; set; }

    [ObservableProperty]
    public partial string DownloadToday { get; set; } = "—";

    private readonly List<long> _downSamples = new(36);
    private readonly List<long> _upSamples = new(36);

    [ObservableProperty]
    public partial IReadOnlyList<long> DownloadHistory { get; set; } = [];

    [ObservableProperty]
    public partial IReadOnlyList<long> UploadHistory { get; set; } = [];

    [ObservableProperty]
    public partial string CurrentDownloadSpeed { get; set; } = "0 B/s";

    [ObservableProperty]
    public partial string CurrentUploadSpeed { get; set; } = "0 B/s";

    [ObservableProperty]
    public partial bool UpdateDownloading { get; set; }

    [ObservableProperty]
    public partial int UpdateDownloadProgress { get; set; }

    [ObservableProperty]
    public partial string UpdateDownloadingText { get; set; } = string.Empty;

    [ObservableProperty]
    public partial bool UpdateReady { get; set; }

    [ObservableProperty]
    public partial string UpdateReadyText { get; set; } = string.Empty;

    [ObservableProperty]
    public partial bool ShowFocusDialog { get; set; }

    [ObservableProperty]
    public partial int FocusDurationIndex { get; set; } = 1;

    [ObservableProperty]
    public partial string FocusAppSearch { get; set; } = string.Empty;

    [ObservableProperty]
    public partial bool ShowBatchDialog { get; set; }

    [ObservableProperty]
    public partial int BatchLifetimeIndex { get; set; }

    [ObservableProperty]
    public partial bool IsDoNotDisturb { get; set; }

    public ObservableCollection<FocusAppOption> FocusApps { get; } = [];

    public ObservableCollection<BatchPromptOption> BatchPrompts { get; } = [];

    public bool IsFocusActive => Backend.IsFocusActive;

    public string FocusStatusText => Backend.FocusEndsAt is { } end
        ? Loc.Format(OverviewLangResourceKind.FocusRemaining, Math.Max(1, (int)Math.Ceiling((end - DateTimeOffset.UtcNow).TotalMinutes)))
        : Loc.Get(OverviewLangResourceKind.FocusOff);

    public int ActiveRuleCount => Backend.Rules.Count(r => r.Enabled);

    public string ActiveProfileName => Backend.ActiveProfile.Name;

    public string? PrivateDnsHost => Backend.GetPrivateDnsHost();

    public bool HasPrivateDns => !string.IsNullOrEmpty(PrivateDnsHost);

    public string PrivateDnsWarningText => PrivateDnsHost is { } h
        ? Loc.Format(OverviewLangResourceKind.PrivateDnsWarning, h)
        : string.Empty;

    public bool ShowSwitchToDirect =>
        Backend.Preferences.ForwardingEnabled &&
        Backend.Preferences.ForwardingUpstream == "socks5" &&
        Status == ProtectionStatus.Failed;

    public string TrafficTileSummary => HasTrafficAccess
        ? $"↓ {DownloadToday} · ↑ {UploadToday}"
        : Loc.Get(OverviewLangResourceKind.GrantUsageAccess);

    public bool ShowUpdateBanner => UpdateDownloading || UpdateReady;

    [ObservableProperty]
    public partial bool ShowPermissionBanner { get; set; }

    [ObservableProperty]
    public partial string PermissionBannerTitle { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string UploadToday { get; set; } = "—";

    public System.Collections.ObjectModel.ObservableCollection<UsageRow> TopUsage { get; } = [];

    [ObservableProperty]
    public partial bool HasNoUsage { get; set; }

    /// <summary>從核心重新讀取狀態（UI 線程）。</summary>
    public void Refresh()
    {
        _syncing = true;
        try
        {
            Status = Backend.Protection;
            Mode = Backend.Mode;
            PendingPrompts = Backend.PendingPromptCount;
            SkippedRules = Backend.SkippedRuleCount;
            IsProtectionOn = Status is not (ProtectionStatus.Off or ProtectionStatus.Revoked);
            ModeIndex = Array.IndexOf(SelectableModes, Mode);
            NotificationsOff = MainActivity.Current is { AreNotificationsEnabled: false };
            // 當前配置文件一律以核心為準；界面不保留自己的選擇（R6）
            var activeId = Backend.ActiveProfile.Id;
            _syncingProfile = true;
            Profiles.Clear();
            foreach (var profile in Backend.Profiles.OrderBy(p => p.SortOrder))
            {
                Profiles.Add(new ProfileChoice(profile.Id, profile.Name, profile.Id == activeId));
            }

            SelectedProfile = Profiles.FirstOrDefault(p => p.Id == activeId) ?? Profiles.FirstOrDefault();
            _syncingProfile = false;
            OnPropertyChanged(nameof(ShowProfiles));
        }
        finally
        {
            _syncing = false;
        }

        OnPropertyChanged(string.Empty);
        OnPropertyChanged(nameof(HasQuietPeriod));
        OnPropertyChanged(nameof(QuietPeriodText));
        _ = RefreshCountsAsync();
    }

    partial void OnIsProtectionOnChanged(bool value)
    {
        if (!_syncing)
        {
            _ = SetProtectionAsync(value);
        }
    }

    partial void OnSelectedProfileChanged(ProfileChoice? value)
    {
        if (_syncingProfile || value is null || value.Id == Backend.ActiveProfile.Id)
        {
            return;
        }

        _ = ActivateProfileAsync(value);
    }

    /// <summary>切換失敗時提示並在 finally 中還原到核心的實際選擇（R6）。</summary>
    private async Task ActivateProfileAsync(ProfileChoice choice)
    {
        ProfileError = null;
        try
        {
            await Backend.ActivateProfileAsync(choice.Id, CancellationToken.None);
        }
        catch (Exception ex) when (ex is ArgumentException or KeyNotFoundException or InvalidOperationException)
        {
            ProfileError = ex.Message;
        }
        finally
        {
            Refresh();
        }
    }

    partial void OnModeIndexChanged(int value)
    {
        // AtomUI Segmented 加載時會自動選中第一項並寫回綁定：只響應與當前模式不同的、由用戶發起的變化
        if (_syncing || value < 0 || value >= SelectableModes.Length || SelectableModes[value] == Mode)
        {
            return;
        }

        _ = Backend.SetModeAsync(SelectableModes[value], CancellationToken.None);
    }

    [RelayCommand]
    private Task ToggleBlockAllAsync() => Backend.SetBlockAllAsync(!IsBlockAll, CancellationToken.None);

    [RelayCommand]
    private void ShowPrompt() => _showPrompt();

    [RelayCommand]
    private void DismissQuietPeriod()
    {
        Backend.DismissQuietPeriod();
        OnPropertyChanged(nameof(HasQuietPeriod));
        OnPropertyChanged(nameof(QuietPeriodText));
    }

    [RelayCommand]
    private async Task EnableNotificationsAsync()
    {
        if (MainActivity.Current is not { } activity)
        {
            return;
        }

        if (!await activity.RequestNotificationPermissionAsync())
        {
            activity.OpenNotificationSettings();
        }

        Refresh();
    }

    [RelayCommand]
    private Task RetryAsync() => SetProtectionAsync(true);

    /// <summary>打開系統的「使用情況存取權」設定；返回界面時 <see cref="Refresh"/> 會重新檢查。</summary>
    [RelayCommand]
    private void GrantUsageAccess() => MainActivity.Current?.StartActivity(Backend.CreateTrafficAccessIntent());

    private async Task SetProtectionAsync(bool enabled)
    {
        if (!enabled)
        {
            Backend.SetProtection(false);
            return;
        }

        IsBusy = true;
        try
        {
            // 1. VPN 授權（系統對話框）
            if (Backend.CreateVpnConsentIntent() is { } consent)
            {
                if (MainActivity.Current is not { } activity || !await activity.RequestVpnConsentAsync(consent))
                {
                    Refresh();
                    return;
                }
            }

            // 2. 通知權限（拒絕也繼續：詢問仍然會在應用內顯示）
            if (MainActivity.Current is { } current)
            {
                await current.RequestNotificationPermissionAsync();
            }

            Backend.SetProtection(true);
        }
        finally
        {
            IsBusy = false;
            Refresh();
        }
    }

    private async Task RefreshCountsAsync()
    {
        try
        {
            BlockedToday = await Backend.CountBlockedSinceAsync(DateTimeOffset.Now.Date, CancellationToken.None);
        }
        catch (Exception ex) when (ex is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            // 統計失敗不影響其他功能
        }

        try
        {
            HasTrafficAccess = Backend.HasTrafficAccess;
            if (!HasTrafficAccess)
            {
                return;
            }

            var today = new DateTimeOffset(DateTimeOffset.Now.Date, DateTimeOffset.Now.Offset);
            var usage = await Backend.GetTrafficAsync(today, today.AddDays(1), CancellationToken.None);
            DownloadToday = Infrastructure.ByteSize.Format(usage.BytesIn);
            UploadToday = Infrastructure.ByteSize.Format(usage.BytesOut);
            var labels = Backend.GetApps().ToDictionary(a => a.App.PackageName, a => a.App.Label, StringComparer.Ordinal);
            var top = usage.ByPackage.OrderByDescending(p => p.Value.In + p.Value.Out).Take(3).ToArray();
            var maxBytes = top.Length > 0 ? Math.Max(1L, top[0].Value.In + top[0].Value.Out) : 1L;
            TopUsage.Clear();
            foreach (var (package, bytes) in top)
            {
                var total = bytes.In + bytes.Out;
                var pct = Math.Clamp(total * 100.0 / maxBytes, 6.0, 100.0);
                TopUsage.Add(new UsageRow(labels.GetValueOrDefault(package) ?? package, Infrastructure.ByteSize.Format(total), pct));
            }

            HasNoUsage = TopUsage.Count == 0;
        }
        catch (Exception ex) when (ex is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            // 統計失敗不影響其他功能
        }
    }

    public void ApplyUpdateState(AppUpdateState state)
    {
        var latest = state.Info?.LatestVersion ?? "";
        UpdateDownloading = state.Downloading;
        UpdateDownloadProgress = state.Progress;
        UpdateDownloadingText = state.Downloading
            ? Loc.Format(OverviewLangResourceKind.UpdateDownloading, latest, state.Progress)
            : string.Empty;
        UpdateReady = !state.Downloading && state.DownloadedPath is not null;
        UpdateReadyText = UpdateReady
            ? Loc.Format(OverviewLangResourceKind.UpdateReady, latest)
            : string.Empty;
        OnPropertyChanged(nameof(ShowUpdateBanner));
    }

    public void UpdatePermissionHealth(PermissionHealthSnapshot health)
    {
        var missing = health.MissingGuideCount;
        ShowPermissionBanner = !Backend.Preferences.PermissionBannerDismissed && missing > 0;
        PermissionBannerTitle = missing > 0 ? Loc.Format(OverviewLangResourceKind.PermBannerTitle, missing) : string.Empty;
    }

    [RelayCommand]
    private void InstallUpdate()
    {
        if (Backend.UpdateManager.State.DownloadedPath is { } path)
        {
            ApkInstaller.Launch(global::Android.App.Application.Context, path);
        }
    }

    [RelayCommand]
    private void OpenPermissionGuide() => _openPermissionGuide();

    [RelayCommand]
    private void DismissPermissionBanner()
    {
        Backend.Preferences.PermissionBannerDismissed = true;
        ShowPermissionBanner = false;
    }

    [RelayCommand]
    private void RefreshOverview() => Refresh();

    [RelayCommand]
    private void SwitchToDirect() => Backend.SwitchToDirectForwarding();

    [RelayCommand]
    private void OpenFocusDialog()
    {
        FocusApps.Clear();
        var apps = Backend.GetApps().Where(a => !a.App.IsSystem).OrderBy(a => a.App.Label, StringComparer.CurrentCultureIgnoreCase);
        foreach (var app in apps)
        {
            FocusApps.Add(new FocusAppOption(app.App.PackageName, app.App.Label));
        }

        FocusAppSearch = string.Empty;
        ShowFocusDialog = true;
    }

    [RelayCommand]
    private void CloseFocusDialog() => ShowFocusDialog = false;

    [RelayCommand]
    private async Task StartFocusAsync()
    {
        var minutes = FocusDurationIndex switch
        {
            0 => 15,
            1 => 30,
            2 => 60,
            _ => 120,
        };
        var selected = FocusApps.Where(a => a.IsSelected).Select(a => a.PackageName).ToList();
        ShowFocusDialog = false;
        await Backend.StartFocusAsync(minutes, selected, CancellationToken.None);
        OnPropertyChanged(nameof(IsFocusActive));
        OnPropertyChanged(nameof(FocusStatusText));
    }

    [RelayCommand]
    private void ExtendFocus()
    {
        Backend.ExtendFocus(15);
        OnPropertyChanged(nameof(FocusStatusText));
    }

    [RelayCommand]
    private async Task StopFocusAsync()
    {
        await Backend.StopFocusAsync(CancellationToken.None);
        OnPropertyChanged(nameof(IsFocusActive));
        OnPropertyChanged(nameof(FocusStatusText));
    }

    [RelayCommand]
    private Task SaveFocusAsProfileAsync() => Backend.SaveFocusAsProfileAsync(CancellationToken.None);

    partial void OnIsDoNotDisturbChanged(bool value)
    {
        if (value && !IsProtectionOn)
        {
            IsProtectionOn = true;
        }

        _ = Backend.SetModeAsync(value ? FilterMode.Blacklist : FilterMode.Ask, CancellationToken.None);
    }

    [RelayCommand]
    private void OpenBatchDialog()
    {
        BatchPrompts.Clear();
        foreach (var p in Backend.GetPendingPrompts())
        {
            var pkg = Engine.Vpn.Platform.PackageAppPath.TryGetPackage(p.App?.Path ?? p.ExecutablePath, out var id) ? id : p.Id.ToString();
            var label = p.App?.Name ?? p.ExecutablePath ?? pkg;
            BatchPrompts.Add(new BatchPromptOption(p.Id, label, pkg));
        }

        ShowBatchDialog = BatchPrompts.Count > 0;
    }

    [RelayCommand]
    private void CloseBatchDialog() => ShowBatchDialog = false;

    [RelayCommand]
    private void BatchSelectAll()
    {
        foreach (var item in BatchPrompts) item.IsSelected = true;
    }

    [RelayCommand]
    private void BatchClearAll()
    {
        foreach (var item in BatchPrompts) item.IsSelected = false;
    }

    [RelayCommand]
    private Task BatchAllowAsync() => ApplyBatchAsync(VerdictAction.Allow);

    [RelayCommand]
    private Task BatchDenyAsync() => ApplyBatchAsync(VerdictAction.Deny);

    private async Task ApplyBatchAsync(VerdictAction action)
    {
        var (lifetime, duration) = BatchLifetimeIndex switch
        {
            1 => (RuleLifetime.Permanent, (TimeSpan?)null),
            2 => (RuleLifetime.Timed, TimeSpan.FromHours(1)),
            _ => (RuleLifetime.UntilRestart, null),
        };
        var selected = BatchPrompts.Where(b => b.IsSelected).ToList();
        foreach (var item in selected)
        {
            await Backend.AnswerPromptAsync(item.PromptId, action, lifetime, duration, CancellationToken.None);
            Platform.PromptNotifier.Cancel(global::Android.App.Application.Context, item.PromptId);
        }

        ShowBatchDialog = false;
        Refresh();
    }

    partial void OnFocusAppSearchChanged(string value)
    {
        var q = value.Trim();
        foreach (var item in FocusApps)
        {
            item.IsVisible = q.Length == 0
                || item.Label.Contains(q, StringComparison.OrdinalIgnoreCase)
                || item.PackageName.Contains(q, StringComparison.OrdinalIgnoreCase);
        }
    }

    private async void SampleSpeed()
    {
        if (!HasTrafficAccess) return;
        try
        {
            var today = new DateTimeOffset(DateTimeOffset.Now.Date, DateTimeOffset.Now.Offset);
            var usage = await Backend.GetTrafficAsync(today, today.AddDays(1), CancellationToken.None);
            var dl = _lastBytesIn >= 0 ? Math.Max(0L, usage.BytesIn - _lastBytesIn) : 0L;
            var ul = _lastBytesOut >= 0 ? Math.Max(0L, usage.BytesOut - _lastBytesOut) : 0L;
            _lastBytesIn = usage.BytesIn;
            _lastBytesOut = usage.BytesOut;
            if (_downSamples.Count >= 36) _downSamples.RemoveAt(0);
            _downSamples.Add(dl);
            if (_upSamples.Count >= 36) _upSamples.RemoveAt(0);
            _upSamples.Add(ul);
            DownloadHistory = _downSamples.ToArray();
            UploadHistory = _upSamples.ToArray();
            CurrentDownloadSpeed = $"{Infrastructure.ByteSize.Format(dl)}/s";
            CurrentUploadSpeed = $"{Infrastructure.ByteSize.Format(ul)}/s";
            Backend.UpdateSpeedIsland($"↓ {CurrentDownloadSpeed} · ↑ {CurrentUploadSpeed}");
            OnPropertyChanged(nameof(FocusStatusText));
            OnPropertyChanged(nameof(TrafficTileSummary));
            OnPropertyChanged(nameof(ActiveRuleCount));
        }
        catch
        {
        }
    }
}

public sealed partial class FocusAppOption(string packageName, string label) : ObservableObject
{
    public string PackageName { get; } = packageName;
    public string Label { get; } = label;

    [ObservableProperty]
    public partial bool IsSelected { get; set; }

    [ObservableProperty]
    public partial bool IsVisible { get; set; } = true;
}

public sealed partial class BatchPromptOption(Guid promptId, string label, string packageName) : ObservableObject
{
    public Guid PromptId { get; } = promptId;
    public string Label { get; } = label;
    public string PackageName { get; } = packageName;

    [ObservableProperty]
    public partial bool IsSelected { get; set; } = true;
}
