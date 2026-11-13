using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Localization;
using NetworkControl.Core.Domain;

namespace NetworkControl.Android.ViewModels;

/// <summary>總覽頁：保護開關、模式、一鍵斷網、今日阻擋、待決詢問。</summary>
public sealed partial class OverviewViewModel : LocalizedViewModel
{
    private static readonly FilterMode[] SelectableModes = [FilterMode.Ask, FilterMode.Whitelist, FilterMode.Blacklist];

    private readonly Action _showPrompt;
    private bool _syncing;

    public OverviewViewModel(UiContext context, Action showPrompt)
        : base(context)
    {
        _showPrompt = showPrompt;
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

    public bool IsProtectionActive => Status is ProtectionStatus.On or ProtectionStatus.Starting;

    public string ModeDescriptionText => ModeDescription(Mode);

    public string SkippedRulesText => Loc.Format(OverviewLangResourceKind.SkippedRules, SkippedRules);

    public int SkippedRules { get; private set; }

    public bool HasSkippedRules => SkippedRules > 0;

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
        }
        finally
        {
            _syncing = false;
        }

        OnPropertyChanged(string.Empty);
        _ = RefreshCountsAsync();
    }

    partial void OnIsProtectionOnChanged(bool value)
    {
        if (!_syncing)
        {
            _ = SetProtectionAsync(value);
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
    }
}
