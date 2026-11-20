using System.Collections.ObjectModel;
using System.Reflection;
using System.Text;
using AtomUI.Localization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Android.Platform;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Profiles;
using NetworkControl.Engine.Vpn.Update;
using NetworkControl.Storage.Serialization;

namespace NetworkControl.Android.ViewModels;

/// <summary>設定頁。</summary>
public sealed partial class SettingsViewModel : LocalizedViewModel
{
    private static readonly LanguageTag?[] Languages = [null, LanguageTags.ZhTW, LanguageTags.ZhCN, LanguageTags.EnUS];
    private static readonly int[] TimeoutSeconds = [15, 30, 60, 120];
    private static readonly NetworkKind?[] AutoKinds = [null, NetworkKind.Wired, NetworkKind.Wifi, NetworkKind.Cellular];

    private bool _syncing;
    private bool _syncingProfiles;
    private string? _importJson;
    private RuleImportPreview? _importPreview;

    public SettingsViewModel(UiContext context)
        : base(context)
    {
        Refresh();
    }

    /// <summary>0 跟隨系統、1 繁體中文、2 简体中文、3 English。</summary>
    [ObservableProperty]
    public partial int LanguageIndex { get; set; } = -1;

    /// <summary>0 跟隨系統、1 淺色、2 深色。</summary>
    [ObservableProperty]
    public partial int ThemeIndex { get; set; } = -1;

    [ObservableProperty]
    public partial int TimeoutIndex { get; set; } = -1;

    /// <summary>0 拒絕、1 允許。</summary>
    [ObservableProperty]
    public partial int DefaultActionIndex { get; set; } = -1;

    [ObservableProperty]
    public partial bool NotificationsEnabled { get; set; }

    /// <summary>配置文件列表（只有一個時設定頁仍可用來改名）。</summary>
    public ObservableCollection<ProfileRow> Profiles { get; } = [];

    [ObservableProperty]
    public partial ProfileRow? SelectedProfile { get; set; }

    [ObservableProperty]
    public partial string ProfileName { get; set; } = "";

    /// <summary>0 詢問、1 白名單、2 黑名單。</summary>
    [ObservableProperty]
    public partial int ProfileModeIndex { get; set; } = -1;

    [ObservableProperty]
    public partial string? ProfileError { get; set; }

    /// <summary>目前網路符合條件時自動切換到選中的配置文件。</summary>
    [ObservableProperty]
    public partial bool AutoSwitchEnabled { get; set; }

    /// <summary>0 不限、1 有線、2 Wi-Fi、3 行動網路。</summary>
    [ObservableProperty]
    public partial int AutoKindIndex { get; set; }

    /// <summary>逗號分隔的 Wi-Fi 名稱；留空表示不按 SSID 限制。</summary>
    [ObservableProperty]
    public partial string AutoSsids { get; set; } = "";

    public bool HasProfileError => !string.IsNullOrEmpty(ProfileError);

    /// <summary>診斷包是否包含應用清單（默認不含包名）。</summary>
    [ObservableProperty]
    public partial bool IncludeAppList { get; set; }

    [ObservableProperty]
    public partial bool IsExporting { get; set; }

    [ObservableProperty]
    public partial int ThemeStyleIndex { get; set; }

    [ObservableProperty]
    public partial string SelectedThemeColorHex { get; set; } = "FF2E7D32";

    [ObservableProperty]
    public partial bool AutoHideBottomBar { get; set; } = true;

    [ObservableProperty]
    public partial bool SpeedIslandEnabled { get; set; }

    [ObservableProperty]
    public partial bool PromptOverlayEnabled { get; set; } = true;

    /// <summary>0 底部、1 頂部。</summary>
    [ObservableProperty]
    public partial int PromptOverlayPositionIndex { get; set; }

    [ObservableProperty]
    public partial bool PromptShowDetails { get; set; } = true;

    [ObservableProperty]
    public partial bool PromptDenyPermanent { get; set; }

    [ObservableProperty]
    public partial bool PromptShowTimedOption { get; set; }

    [ObservableProperty]
    public partial bool AppLockEnabled { get; set; }

    [ObservableProperty]
    public partial bool AllowSystemApps { get; set; }

    [ObservableProperty]
    public partial bool DnsBypassProtection { get; set; } = true;

    [ObservableProperty]
    public partial bool Socks5Udp { get; set; } = true;

    [ObservableProperty]
    public partial bool ShowReleaseNotesDialog { get; set; }

    [ObservableProperty]
    public partial bool ShowBlocklistsDialog { get; set; }

    [ObservableProperty]
    public partial bool BlocklistsUpdating { get; set; }

    [ObservableProperty]
    public partial string? BlocklistsStatus { get; set; }

    [ObservableProperty]
    public partial string CustomBlocklistName { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string CustomBlocklistUrl { get; set; } = string.Empty;

    public ObservableCollection<BlocklistSourceRow> BlocklistSources { get; } = [];

    public string BlocklistsSummary => $"{Backend.Blocklists.TotalEntries:N0} · {Backend.Blocklists.EnabledCount}";

    public int TrustedPublishersCount => Backend.Preferences.TrustedPublishers.Count;

    public string ReleaseNotesText =>
        "v0.3.2 (AtomUI)\n" +
        "- 系統級紅魔風格懸浮詢問窗與應用內詢問卡片完全對齊\n" +
        "- 總覽頁補齊 3×2 Bento 網格（專注模式、勿擾模式、批量詢問處理、生效規則與今日流量）\n" +
        "- 記錄頁新增「已放行」分頁、搜尋篩選、詳情彈窗一鍵放行網域與 CSV 匯出\n" +
        "- 應用頁新增應用詳情彈窗（簽章指紋、信任發布者、近期連線目標）\n" +
        "- 設定頁補齊 6 套真實視覺風格調色盤、主題強調色盤、SOCKS5 帳密/UDP/防 DNS 繞過、應用鎖與更新日誌\n\n" +
        "v0.3.1 (AtomUI)\n" +
        "- 獨立 AtomUI 版本號與雲端自動發版流水線\n" +
        "- 檢查更新與 Kotlin 版通道徹底隔離";

    [ObservableProperty]
    public partial bool OfflineMode { get; set; }

    [ObservableProperty]
    public partial bool CloneIndependent { get; set; }

    [ObservableProperty]
    public partial bool ForwardingEnabled { get; set; }

    [ObservableProperty]
    public partial int ForwardingUpstreamIndex { get; set; }

    [ObservableProperty]
    public partial string Socks5Host { get; set; } = "127.0.0.1";

    [ObservableProperty]
    public partial string Socks5PortText { get; set; } = "7890";

    [ObservableProperty]
    public partial string Socks5Username { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string Socks5Password { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string? ProxyDiagStatus { get; set; }

    [ObservableProperty]
    public partial bool BlocklistsEnabled { get; set; }

    [ObservableProperty]
    public partial string PermissionHealthSummary { get; set; } = "0 / 6";

    [ObservableProperty]
    public partial bool ShowWebDavDialog { get; set; }

    [ObservableProperty]
    public partial string WebDavUrl { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string WebDavUsername { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string WebDavPassword { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string WebDavPassphrase { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string? WebDavStatus { get; set; }

    [ObservableProperty]
    public partial bool WebDavBusy { get; set; }

    [ObservableProperty]
    public partial bool UpdateChecking { get; set; }

    [ObservableProperty]
    public partial string? UpdateStatusText { get; set; }

    [ObservableProperty]
    public partial string? DiagnosticsStatus { get; set; }

    [ObservableProperty]
    public partial bool IsExportingRules { get; set; }

    [ObservableProperty]
    public partial bool IsImportingRules { get; set; }

    [ObservableProperty]
    public partial string? RuleTransferStatus { get; set; }

    [ObservableProperty]
    public partial bool IsImportDialogOpen { get; set; }

    /// <summary>0 合併、1 覆寫。</summary>
    [ObservableProperty]
    public partial int ImportModeIndex { get; set; }

    [ObservableProperty]
    public partial string? ImportSummary { get; set; }

    [ObservableProperty]
    public partial string? ImportIneffective { get; set; }

    public bool HasProfiles => Profiles.Count > 0;

    public bool HasSelectedProfile => SelectedProfile is not null;

    public bool CanDeleteSelected => SelectedProfile?.CanDelete == true;

    public bool CanActivateSelected => SelectedProfile?.CanActivate == true;

    public static IReadOnlyList<string> LanguageNames { get; } = ["", "繁體中文", "简体中文", "English"];

    public string NotificationsText => Loc.Get(NotificationsEnabled ? SettingsLangResourceKind.NotificationsOn : SettingsLangResourceKind.NotificationsOff);

    public string TrafficAccessText => Loc.Get(Backend.HasTrafficAccess ? SettingsLangResourceKind.TrafficGranted : SettingsLangResourceKind.TrafficNotGranted);

    public string TimeoutText(int index) => Loc.Format(SettingsLangResourceKind.Seconds, TimeoutSeconds[index]);

    public string Timeout15 => TimeoutText(0);

    public string Timeout30 => TimeoutText(1);

    public string Timeout60 => TimeoutText(2);

    public string Timeout120 => TimeoutText(3);

    public static string Version { get; } =
        typeof(SettingsViewModel).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "0.0.0";

    public string StatisticsText
    {
        get
        {
            var c = Backend.Counters;
            return Loc.Format(SettingsLangResourceKind.StatisticsFormat, c.Packets, c.Attempts, c.Reported);
        }
    }

    public void Refresh()
    {
        _syncing = true;
        try
        {
            var language = Loc.Preference;
            LanguageIndex = Math.Max(0, Array.FindIndex(Languages, l => l?.ToString() == language));
            ThemeIndex = (int)Context.Appearance.Preference;
            var settings = Backend.Settings;
            var seconds = (int)settings.PromptTimeout.TotalSeconds;
            TimeoutIndex = Array.IndexOf(TimeoutSeconds, seconds) is var i and >= 0 ? i : 1;
            DefaultActionIndex = settings.PromptDefaultAction == VerdictAction.Allow ? 1 : 0;
            NotificationsEnabled = MainActivity.Current?.AreNotificationsEnabled ?? false;

            var prefs = Backend.Preferences;
            ThemeStyleIndex = (int)prefs.ThemeStyle;
            SelectedThemeColorHex = prefs.ThemeColorArgb.ToString("X8", System.Globalization.CultureInfo.InvariantCulture);
            AutoHideBottomBar = prefs.AutoHideBottomBar;
            SpeedIslandEnabled = prefs.SpeedIslandEnabled;
            PromptOverlayEnabled = prefs.PromptOverlayEnabled;
            PromptOverlayPositionIndex = prefs.PromptOverlayPosition == "top" ? 1 : 0;
            PromptShowDetails = prefs.PromptShowDetails;
            PromptDenyPermanent = prefs.PromptDenyPermanent;
            PromptShowTimedOption = prefs.PromptShowTimedOption;
            AppLockEnabled = prefs.AppLockEnabled;
            AllowSystemApps = prefs.AllowSystemApps;
            DnsBypassProtection = prefs.DnsBypassProtection;
            Socks5Udp = prefs.Socks5Udp;
            OfflineMode = prefs.OfflineMode;
            CloneIndependent = !prefs.CloneAppsInheritPrimary;
            ForwardingEnabled = prefs.ForwardingEnabled;
            ForwardingUpstreamIndex = prefs.ForwardingUpstream == "socks5" ? 1 : 0;
            Socks5Host = prefs.Socks5Host;
            Socks5PortText = prefs.Socks5Port.ToString(System.Globalization.CultureInfo.InvariantCulture);
            Socks5Username = prefs.Socks5Username;
            Socks5Password = prefs.Socks5Password;
            BlocklistsEnabled = prefs.BlocklistsEnabled;
            WebDavUrl = prefs.WebDavUrl;
            WebDavUsername = prefs.WebDavUsername;
            WebDavPassword = prefs.WebDavPassword;
            WebDavPassphrase = prefs.WebDavPassphrase;
        }
        finally
        {
            _syncing = false;
        }

        RefreshProfiles();
        UpdatePermissionHealth(Backend.CheckPermissionHealth());
        OnPropertyChanged(nameof(NotificationsText));
        OnPropertyChanged(nameof(TrafficAccessText));
        OnPropertyChanged(nameof(StatisticsText));
    }

    partial void OnLanguageIndexChanged(int value)
    {
        if (!_syncing && value >= 0 && value < Languages.Length)
        {
            Loc.SetPreference(Languages[value]);
        }
    }

    partial void OnThemeIndexChanged(int value)
    {
        if (!_syncing && value >= 0)
        {
            _ = Context.Appearance.SetPreferenceAsync((ThemePreference)value);
        }
    }

    partial void OnTimeoutIndexChanged(int value)
    {
        if (!_syncing && value >= 0 && value < TimeoutSeconds.Length)
        {
            var timeout = TimeSpan.FromSeconds(TimeoutSeconds[value]);
            _ = Backend.UpdateSettingsAsync(s => s with { PromptTimeout = timeout }, CancellationToken.None);
        }
    }

    partial void OnDefaultActionIndexChanged(int value)
    {
        if (!_syncing && value >= 0)
        {
            var action = value == 1 ? VerdictAction.Allow : VerdictAction.Deny;
            _ = Backend.UpdateSettingsAsync(s => s with { PromptDefaultAction = action }, CancellationToken.None);
        }
    }

    partial void OnSelectedProfileChanged(ProfileRow? value)
    {
        if (_syncingProfiles)
        {
            return;
        }

        ProfileName = value?.Name ?? "";
        ProfileModeIndex = value?.ModeIndex ?? -1;
        var condition = value is null
            ? null
            : Backend.Profiles.FirstOrDefault(p => p.Id == value.Id)?.AutoActivate;
        _syncingProfiles = true;
        try
        {
            AutoSwitchEnabled = condition is { IsEmpty: false };
            AutoKindIndex = Math.Max(0, Array.IndexOf(AutoKinds, condition?.Kind));
            AutoSsids = condition is null ? "" : string.Join(", ", condition.Ssids);
        }
        finally
        {
            _syncingProfiles = false;
        }

        OnPropertyChanged(nameof(HasSelectedProfile));
        OnPropertyChanged(nameof(CanDeleteSelected));
        OnPropertyChanged(nameof(CanActivateSelected));
    }

    partial void OnProfileErrorChanged(string? value) => OnPropertyChanged(nameof(HasProfileError));

    /// <summary>把目前 Wi-Fi 的 SSID 填入條件；沒有定位權限時讀不到 SSID。</summary>
    [RelayCommand]
    private void UseCurrentWifi()
    {
        ProfileError = null;
        if (Backend.CurrentNetwork.Ssid is not { Length: > 0 } ssid)
        {
            ProfileError = Loc.Get(SettingsLangResourceKind.NetworkSsidUnavailable);
            return;
        }

        AutoSwitchEnabled = true;
        AutoKindIndex = Array.IndexOf(AutoKinds, NetworkKind.Wifi);
        var names = AutoSsids
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Append(ssid)
            .Distinct(StringComparer.OrdinalIgnoreCase);
        AutoSsids = string.Join(", ", names);
    }

    private AutoActivateCondition? BuildAutoActivate()
    {
        if (!AutoSwitchEnabled)
        {
            return null;
        }

        var kind = AutoKindIndex >= 0 && AutoKindIndex < AutoKinds.Length ? AutoKinds[AutoKindIndex] : null;
        var ssids = AutoSsids
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
        var condition = new AutoActivateCondition(kind, ssids, []);
        return condition.IsEmpty ? null : condition;
    }

    partial void OnProfileModeIndexChanged(int value)
    {
        if (!_syncingProfiles)
        {
            ProfileError = null;
        }
    }

    [RelayCommand]
    private async Task AddProfileAsync()
    {
        ProfileError = null;
        try
        {
            await Backend.CreateProfileAsync(ProfileName.Trim(), SelectedMode(), BuildAutoActivate(), CancellationToken.None);
            ProfileName = "";
            RefreshProfiles();
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException)
        {
            ProfileError = ex.Message;
        }
    }

    [RelayCommand]
    private async Task SaveProfileAsync()
    {
        if (SelectedProfile is not { } selected)
        {
            return;
        }

        ProfileError = null;
        try
        {
            await Backend.UpdateProfileAsync(selected.Id, ProfileName.Trim(), SelectedMode(), BuildAutoActivate(), CancellationToken.None);
            RefreshProfiles();
        }
        catch (Exception ex) when (ex is ArgumentException or KeyNotFoundException or InvalidOperationException)
        {
            ProfileError = ex.Message;
        }
    }

    [RelayCommand]
    private async Task ActivateProfileAsync()
    {
        if (SelectedProfile is { } selected)
        {
            await Backend.ActivateProfileAsync(selected.Id, CancellationToken.None);
            RefreshProfiles();
        }
    }

    [RelayCommand]
    private async Task DeleteProfileAsync()
    {
        if (SelectedProfile is not { CanDelete: true } selected)
        {
            return;
        }

        ProfileError = null;
        try
        {
            await Backend.DeleteProfileAsync(selected.Id, CancellationToken.None);
            RefreshProfiles();
        }
        catch (Exception ex) when (ex is ArgumentException or KeyNotFoundException or InvalidOperationException)
        {
            ProfileError = ex.Message;
        }
    }

    private void RefreshProfiles()
    {
        var selectedId = SelectedProfile?.Id;
        _syncingProfiles = true;
        Profiles.Clear();
        foreach (var profile in Backend.Profiles.OrderBy(p => p.SortOrder))
        {
            Profiles.Add(new ProfileRow(
                profile.Id,
                profile.Name,
                profile.Mode,
                ModeText(profile.Mode),
                Loc.Format(SettingsLangResourceKind.ProfileRuleCount, Backend.GetRuleCount(profile.Id)),
                profile.Id == Backend.ActiveProfile.Id,
                profile.Id == Profile.DefaultId));
        }

        SelectedProfile = Profiles.FirstOrDefault(p => p.Id == selectedId)
            ?? Profiles.FirstOrDefault(p => p.IsActive)
            ?? Profiles.FirstOrDefault();
        _syncingProfiles = false;
        ProfileName = SelectedProfile?.Name ?? "";
        ProfileModeIndex = SelectedProfile?.ModeIndex ?? -1;
        OnPropertyChanged(nameof(HasProfiles));
        OnPropertyChanged(nameof(HasSelectedProfile));
        OnPropertyChanged(nameof(CanDeleteSelected));
        OnPropertyChanged(nameof(CanActivateSelected));
    }

    private FilterMode SelectedMode() => ProfileModeIndex switch
    {
        1 => FilterMode.Whitelist,
        2 => FilterMode.Blacklist,
        _ => FilterMode.Ask,
    };


    private RuleImportMode ImportMode => ImportModeIndex == 1 ? RuleImportMode.Replace : RuleImportMode.Merge;

    partial void OnImportModeIndexChanged(int value)
    {
        if (IsImportDialogOpen)
        {
            TryRefreshImportPreview();
        }
    }

    [RelayCommand]
    private async Task ExportRulesAsync()
    {
        if (IsExportingRules)
        {
            return;
        }

        IsExportingRules = true;
        RuleTransferStatus = null;
        try
        {
            var json = await Backend.ExportRulesAsync(CancellationToken.None);
            if (MainActivity.Current is not { } activity)
            {
                return;
            }

            var fileName = $"networkcontrol-rules-{DateTime.Now:yyyyMMdd}.json";
            var uri = await activity.RequestCreateDocumentAsync(fileName, "application/json", CancellationToken.None);
            if (uri is null)
            {
                return;
            }

            var context = global::Android.App.Application.Context;
            await using var output = context.ContentResolver?.OpenOutputStream(uri, "w")
                ?? throw new IOException("Cannot open the selected file.");
            await using var writer = new StreamWriter(output, new UTF8Encoding(false));
            await writer.WriteAsync(json.AsMemory(), CancellationToken.None);
            RuleTransferStatus = Loc.Format(SettingsLangResourceKind.ExportSaved, fileName);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            RuleTransferStatus = Loc.Format(SettingsLangResourceKind.ExportFailed, ex.Message);
        }
        finally
        {
            IsExportingRules = false;
        }
    }

    [RelayCommand]
    private async Task ImportRulesAsync()
    {
        RuleTransferStatus = null;
        if (MainActivity.Current is not { } activity)
        {
            return;
        }

        try
        {
            var uri = await activity.RequestOpenDocumentAsync(["application/json", "*/*"], CancellationToken.None);
            if (uri is null)
            {
                return;
            }

            _importJson = await ReadTextAsync(uri, 20 * 1024 * 1024, CancellationToken.None);
            ImportModeIndex = 0;
            if (!TryRefreshImportPreview())
            {
                _importJson = null;
                return;
            }

            IsImportDialogOpen = true;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _importJson = null;
            RuleTransferStatus = Loc.Format(SettingsLangResourceKind.ImportFailed, ex.Message);
        }
    }

    [RelayCommand]
    private async Task ConfirmImportAsync()
    {
        if (_importJson is null || _importPreview is null || IsImportingRules)
        {
            return;
        }

        IsImportingRules = true;
        try
        {
            var result = await Backend.ApplyImportAsync(_importJson, ImportMode, CancellationToken.None);
            RuleTransferStatus = Loc.Format(SettingsLangResourceKind.ImportDone, result.Added, result.Updated, result.Removed, result.Unchanged);
            IsImportDialogOpen = false;
            _importJson = null;
            _importPreview = null;
            RefreshProfiles();
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            RuleTransferStatus = Loc.Format(SettingsLangResourceKind.ImportFailed, ex.Message);
        }
        finally
        {
            IsImportingRules = false;
        }
    }

    [RelayCommand]
    private void CancelImport()
    {
        IsImportDialogOpen = false;
        _importJson = null;
        _importPreview = null;
    }

    private bool TryRefreshImportPreview()
    {
        if (_importJson is null)
        {
            return false;
        }

        try
        {
            _importPreview = Backend.PreviewImport(_importJson, ImportMode);
            ImportSummary = Loc.Format(
                SettingsLangResourceKind.ImportPreview,
                _importPreview.Total,
                _importPreview.Added,
                _importPreview.Updated,
                _importPreview.Removed,
                _importPreview.Unchanged);
            ImportIneffective = _importPreview.Ineffective > 0
                ? Loc.Format(SettingsLangResourceKind.ImportIneffective, _importPreview.Ineffective)
                : null;
            return true;
        }
        catch (FormatException ex)
        {
            _importPreview = null;
            ImportSummary = null;
            ImportIneffective = null;
            RuleTransferStatus = Loc.Format(SettingsLangResourceKind.ImportFailed, ex.Message);
            return false;
        }
    }

    private static async Task<string> ReadTextAsync(global::Android.Net.Uri uri, int maxLength, CancellationToken cancellationToken)
    {
        var context = global::Android.App.Application.Context;
        await using var input = context.ContentResolver?.OpenInputStream(uri)
            ?? throw new IOException("Cannot open the selected file.");
        using var reader = new StreamReader(input, Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
        var builder = new StringBuilder();
        var buffer = new char[8192];
        int read;
        while ((read = await reader.ReadAsync(buffer.AsMemory(), cancellationToken)) > 0)
        {
            builder.Append(buffer, 0, read);
            if (builder.Length > maxLength)
            {
                throw new IOException("The selected file is too large.");
            }
        }

        return builder.ToString();
    }

    [RelayCommand]
    private async Task ExportDiagnosticsAsync()
    {
        if (IsExporting)
        {
            return;
        }

        IsExporting = true;
        DiagnosticsStatus = null;
        try
        {
            var result = await DiagnosticsExporter.ExportAsync(Backend, IncludeAppList, CancellationToken.None);
            DiagnosticsStatus = result.Saved
                ? Loc.Format(SettingsLangResourceKind.ExportSaved, result.FileName)
                : result.Canceled
                    ? Loc.Get(SettingsLangResourceKind.ExportCanceled)
                    : Loc.Format(SettingsLangResourceKind.ExportFailed, result.Error ?? "");
        }
        catch (Exception ex)
        {
            DiagnosticsStatus = Loc.Format(SettingsLangResourceKind.ExportFailed, ex.Message);
        }
        finally
        {
            IsExporting = false;
        }
    }

    [RelayCommand]
    private void OpenNotificationSettings() => MainActivity.Current?.OpenNotificationSettings();

    [RelayCommand]
    private void OpenVpnSettings() => MainActivity.Current?.OpenVpnSettings();

    [RelayCommand]
    private void OpenBatterySettings() => MainActivity.Current?.OpenBatterySettings();

    [RelayCommand]
    private void OpenTrafficSettings() => MainActivity.Current?.StartActivity(Backend.CreateTrafficAccessIntent());

    partial void OnThemeStyleIndexChanged(int value)
    {
        if (!_syncing && value >= 0 && value <= 5)
        {
            _ = Context.Appearance.SetThemeStyleAsync((AppThemeStyle)value);
        }
    }

    [RelayCommand]
    private void SelectThemeStyle(string indexText)
    {
        if (int.TryParse(indexText, System.Globalization.CultureInfo.InvariantCulture, out var idx) && idx is >= 0 and <= 5)
        {
            ThemeStyleIndex = idx;
        }
    }

    [RelayCommand]
    private void SelectThemeColor(string hex)
    {
        if (uint.TryParse(hex.TrimStart('#'), System.Globalization.NumberStyles.HexNumber, System.Globalization.CultureInfo.InvariantCulture, out var argb))
        {
            SelectedThemeColorHex = argb.ToString("X8", System.Globalization.CultureInfo.InvariantCulture);
            _ = Context.Appearance.SetThemeColorAsync(argb);
        }
    }

    [RelayCommand]
    private void ClearTrustedPublishers()
    {
        Backend.ClearTrustedPublishers();
        OnPropertyChanged(nameof(TrustedPublishersCount));
    }

    [RelayCommand]
    private void OpenReleaseNotes() => ShowReleaseNotesDialog = true;

    [RelayCommand]
    private void CloseReleaseNotes() => ShowReleaseNotesDialog = false;

    [RelayCommand]
    private void OpenBlocklistsDialog()
    {
        RefreshBlocklistSources();
        ShowBlocklistsDialog = true;
    }

    [RelayCommand]
    private void CloseBlocklistsDialog() => ShowBlocklistsDialog = false;

    [RelayCommand]
    private async Task UpdateBlocklistsAsync()
    {
        if (Backend.Preferences.OfflineMode)
        {
            BlocklistsStatus = Loc.Get(SettingsLangResourceKind.OfflineModeBlocked);
            return;
        }

        BlocklistsUpdating = true;
        BlocklistsStatus = null;
        try
        {
            var (updated, failed) = await Backend.Blocklists.UpdateAllAsync(false, CancellationToken.None);
            RefreshBlocklistSources();
            BlocklistsStatus = $"✓ {updated} / ✗ {failed} · {Backend.Blocklists.TotalEntries:N0}";
        }
        catch (Exception ex)
        {
            BlocklistsStatus = ex.Message;
        }
        finally
        {
            BlocklistsUpdating = false;
            OnPropertyChanged(nameof(BlocklistsSummary));
        }
    }

    [RelayCommand]
    private void AddCustomBlocklist()
    {
        try
        {
            Backend.Blocklists.AddCustomSource(CustomBlocklistName, CustomBlocklistUrl);
            CustomBlocklistName = string.Empty;
            CustomBlocklistUrl = string.Empty;
            RefreshBlocklistSources();
        }
        catch (Exception ex)
        {
            BlocklistsStatus = ex.Message;
        }
    }

    private void RefreshBlocklistSources()
    {
        BlocklistSources.Clear();
        foreach (var src in Backend.Blocklists.GetSources())
        {
            BlocklistSources.Add(new BlocklistSourceRow(src, Backend.Blocklists, () =>
            {
                OnPropertyChanged(nameof(BlocklistsSummary));
                RefreshBlocklistSources();
            }));
        }

        OnPropertyChanged(nameof(BlocklistsSummary));
    }

    partial void OnAppLockEnabledChanged(bool value)
    {
        if (!_syncing) Backend.Preferences.AppLockEnabled = value;
    }

    partial void OnAllowSystemAppsChanged(bool value)
    {
        if (!_syncing) Backend.Preferences.AllowSystemApps = value;
    }

    partial void OnDnsBypassProtectionChanged(bool value)
    {
        if (!_syncing) Backend.Preferences.DnsBypassProtection = value;
    }

    partial void OnSocks5UdpChanged(bool value)
    {
        if (!_syncing) Backend.Preferences.Socks5Udp = value;
    }

    partial void OnAutoHideBottomBarChanged(bool value)
    {
        if (!_syncing) Backend.Preferences.AutoHideBottomBar = value;
    }

    partial void OnSpeedIslandEnabledChanged(bool value)
    {
        if (!_syncing)
        {
            if (value && MainActivity.Current is { } act && !global::Android.Provider.Settings.CanDrawOverlays(act))
            {
                AndroidPermissionHelper.OpenOverlaySettings(act);
            }
            Backend.Preferences.SpeedIslandEnabled = value;
            Backend.UpdateSpeedIsland("↓ 0 B/s · ↑ 0 B/s");
        }
    }

    partial void OnPromptOverlayEnabledChanged(bool value)
    {
        if (!_syncing)
        {
            if (value && MainActivity.Current is { } act && !global::Android.Provider.Settings.CanDrawOverlays(act))
            {
                AndroidPermissionHelper.OpenOverlaySettings(act);
            }

            Backend.Preferences.PromptOverlayEnabled = value;
        }
    }

    partial void OnPromptOverlayPositionIndexChanged(int value)
    {
        if (!_syncing)
        {
            Backend.Preferences.PromptOverlayPosition = value == 1 ? "top" : "bottom";
        }
    }

    partial void OnPromptShowDetailsChanged(bool value)
    {
        if (!_syncing)
        {
            Backend.Preferences.PromptShowDetails = value;
        }
    }

    partial void OnPromptDenyPermanentChanged(bool value)
    {
        if (!_syncing)
        {
            Backend.Preferences.PromptDenyPermanent = value;
        }
    }

    partial void OnPromptShowTimedOptionChanged(bool value)
    {
        if (!_syncing)
        {
            Backend.Preferences.PromptShowTimedOption = value;
        }
    }

    partial void OnOfflineModeChanged(bool value)
    {
        if (!_syncing) Backend.Preferences.OfflineMode = value;
    }

    partial void OnCloneIndependentChanged(bool value)
    {
        if (!_syncing) Backend.Preferences.CloneAppsInheritPrimary = !value;
    }

    partial void OnForwardingEnabledChanged(bool value)
    {
        if (!_syncing)
        {
            Backend.Preferences.ForwardingEnabled = value;
            Backend.RestartTunnelIfActive();
        }
    }

    partial void OnForwardingUpstreamIndexChanged(int value)
    {
        if (!_syncing)
        {
            Backend.Preferences.ForwardingUpstream = value == 1 ? "socks5" : "direct";
            Backend.RestartTunnelIfActive();
        }
    }

    partial void OnBlocklistsEnabledChanged(bool value)
    {
        if (!_syncing) Backend.Preferences.BlocklistsEnabled = value;
    }

    public void UpdatePermissionHealth(PermissionHealthSnapshot health)
    {
        PermissionHealthSummary = $"{health.ReadyCount} / 6";
    }

    public void ApplyUpdateState(AppUpdateState state)
    {
        UpdateChecking = state.Checking;
        if (!string.IsNullOrEmpty(state.Error))
        {
            UpdateStatusText = state.Error;
        }
        else if (state.Info is { HasUpdate: true } info)
        {
            UpdateStatusText = Loc.Format(OverviewLangResourceKind.UpdateAvailableTitle, info.LatestVersion ?? "");
        }
        else if (state.Info is not null && !state.Checking)
        {
            UpdateStatusText = Loc.Get(SettingsLangResourceKind.UpToDate);
        }
    }

    [RelayCommand]
    private async Task TestProxyAsync()
    {
        SaveSocks5Fields();
        ProxyDiagStatus = "…";
        var res = await Backend.DiagnoseProxyAsync(CancellationToken.None);
        ProxyDiagStatus = res.InternetReachable
            ? $"✓ Port · ✓ Auth · ✓ Internet ({res.LatencyMs} ms)"
            : $"✗ {res.ErrorStage}: {res.ErrorMessage}";
    }

    [RelayCommand]
    private void SaveLocalBackup()
    {
        try
        {
            var path = Backend.SaveLocalBackupToDownloads();
            RuleTransferStatus = path;
        }
        catch (Exception ex)
        {
            RuleTransferStatus = Loc.Format(SettingsLangResourceKind.ExportFailed, ex.Message);
        }
    }

    [RelayCommand]
    private void RestoreLocalBackup()
    {
        try
        {
            var json = Backend.ReadLocalBackupFromDownloads();
            _importJson = json;
            _importPreview = Backend.PreviewImport(json, RuleImportMode.Merge);
            ImportSummary = Loc.Format(
                SettingsLangResourceKind.ImportPreview,
                _importPreview.Total, _importPreview.Added, _importPreview.Updated, _importPreview.Removed, _importPreview.Unchanged);
            IsImportDialogOpen = true;
        }
        catch (Exception ex)
        {
            RuleTransferStatus = Loc.Format(SettingsLangResourceKind.ImportFailed, ex.Message);
        }
    }

    [RelayCommand]
    private void OpenWebDavDialog()
    {
        WebDavStatus = null;
        ShowWebDavDialog = true;
    }

    [RelayCommand]
    private void CloseWebDavDialog()
    {
        SaveWebDavFields();
        ShowWebDavDialog = false;
    }

    [RelayCommand]
    private async Task UploadWebDavAsync()
    {
        if (OfflineMode)
        {
            WebDavStatus = Loc.Get(SettingsLangResourceKind.OfflineModeBlocked);
            return;
        }

        SaveWebDavFields();
        WebDavBusy = true;
        WebDavStatus = null;
        try
        {
            var url = await Backend.UploadWebDavBackupAsync(CancellationToken.None);
            WebDavStatus = $"✓ {url}";
        }
        catch (Exception ex)
        {
            WebDavStatus = Loc.Format(SettingsLangResourceKind.ExportFailed, ex.Message);
        }
        finally
        {
            WebDavBusy = false;
        }
    }

    [RelayCommand]
    private async Task DownloadWebDavAsync()
    {
        if (OfflineMode)
        {
            WebDavStatus = Loc.Get(SettingsLangResourceKind.OfflineModeBlocked);
            return;
        }

        SaveWebDavFields();
        WebDavBusy = true;
        WebDavStatus = null;
        try
        {
            var json = await Backend.DownloadWebDavBackupAsync(CancellationToken.None);
            _importJson = json;
            _importPreview = Backend.PreviewImport(json, RuleImportMode.Merge);
            ImportSummary = Loc.Format(
                SettingsLangResourceKind.ImportPreview,
                _importPreview.Total, _importPreview.Added, _importPreview.Updated, _importPreview.Removed, _importPreview.Unchanged);
            ShowWebDavDialog = false;
            IsImportDialogOpen = true;
        }
        catch (Exception ex)
        {
            WebDavStatus = Loc.Format(SettingsLangResourceKind.ImportFailed, ex.Message);
        }
        finally
        {
            WebDavBusy = false;
        }
    }

    [RelayCommand]
    private async Task CheckUpdatesAsync()
    {
        if (OfflineMode)
        {
            UpdateStatusText = Loc.Get(SettingsLangResourceKind.OfflineModeBlocked);
            return;
        }

        await Backend.UpdateManager.CheckManuallyAsync(CancellationToken.None);
    }

    private void SaveSocks5Fields()
    {
        Backend.Preferences.Socks5Host = Socks5Host.Trim();
        if (int.TryParse(Socks5PortText.Trim(), out var port) && port is > 0 and <= 65535)
        {
            Backend.Preferences.Socks5Port = port;
        }
        Backend.Preferences.Socks5Username = Socks5Username;
        Backend.Preferences.Socks5Password = Socks5Password;
    }

    private void SaveWebDavFields()
    {
        Backend.Preferences.WebDavUrl = WebDavUrl;
        Backend.Preferences.WebDavUsername = WebDavUsername;
        Backend.Preferences.WebDavPassword = WebDavPassword;
        Backend.Preferences.WebDavPassphrase = WebDavPassphrase;
    }
}

public sealed partial class BlocklistSourceRow : ObservableObject
{
    private readonly NetworkControl.Engine.Vpn.Blocklists.BlocklistManager _manager;
    private readonly Action _refresh;
    private bool _syncing;

    public BlocklistSourceRow(
        NetworkControl.Engine.Vpn.Blocklists.BlocklistSourceState state,
        NetworkControl.Engine.Vpn.Blocklists.BlocklistManager manager,
        Action refresh)
    {
        _manager = manager;
        _refresh = refresh;
        Id = state.Id;
        Name = state.Name;
        IsCustom = !state.IsBuiltin;
        Subtitle = state.LastError is { Length: > 0 } err
            ? err
            : state.EntryCount > 0
                ? $"{state.EntryCount:N0}"
                : "—";
        _syncing = true;
        Enabled = state.Enabled;
        _syncing = false;
    }

    public string Id { get; }
    public string Name { get; }
    public bool IsCustom { get; }
    public string Subtitle { get; }

    [ObservableProperty]
    public partial bool Enabled { get; set; }

    partial void OnEnabledChanged(bool value)
    {
        if (!_syncing)
        {
            _manager.SetSourceEnabled(Id, value);
            _refresh();
        }
    }

    [RelayCommand]
    private void Remove()
    {
        _manager.RemoveCustomSource(Id);
        _refresh();
    }
}
