using System.Collections.ObjectModel;
using AtomUI.Localization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Google.Protobuf.WellKnownTypes;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using NetworkControl.App.Services;
using NetworkControl.App.Views;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>
/// 設置頁。界面設置（語言、主題、登錄時啟動）即時生效並保存在本機；
/// 服務設置保存在核心服務中，對本機所有用戶生效，點擊「儲存」後提交。
/// </summary>
public sealed partial class SettingsViewModel : PageViewModel
{
    private readonly ServiceConnection _connection;
    private readonly AppearanceService _appearance;
    private readonly AutoStartService _autoStart;
    private readonly FeedbackService _feedback;
    private readonly DiagnosticsExportFlow _diagnostics;
    private readonly RuleTransferFlow _transfer;
    private readonly DialogService _dialogs;
    private Proto.UserSettings? _loaded;
    private bool _loading;

    public SettingsViewModel(
        ServiceConnection connection,
        AppearanceService appearance,
        AutoStartService autoStart,
        FeedbackService feedback,
        DiagnosticsExportFlow diagnostics,
        RuleTransferFlow transfer,
        DialogService dialogs,
        DisplayText text)
        : base(text)
    {
        _dialogs = dialogs;
        _transfer = transfer;
        _diagnostics = diagnostics;
        _connection = connection;
        connection.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(ServiceConnection.Status))
            {
                OnPropertyChanged(nameof(CanModify));
            }
        };
        _appearance = appearance;
        _autoStart = autoStart;
        _feedback = feedback;

        Languages =
        [
            new Choice<LanguageTag?>(null, () => Loc.Get(SettingsLangResourceKind.LanguageFollowSystem)),
            .. Loc.Languages.Select(l => new Choice<LanguageTag?>(l.Tag, () => l.NativeName)),
        ];
        DefaultActions =
        [
            new Choice<Proto.VerdictAction>(Proto.VerdictAction.Deny, () => Loc.Get(CommonLangResourceKind.Deny)),
            new Choice<Proto.VerdictAction>(Proto.VerdictAction.Allow, () => Loc.Get(CommonLangResourceKind.Allow)),
        ];
        FailPolicies =
        [
            new Choice<Proto.FailPolicy>(Proto.FailPolicy.FailOpen, () => Loc.Get(SettingsLangResourceKind.FailOpen)),
            new Choice<Proto.FailPolicy>(Proto.FailPolicy.FailClosed, () => Loc.Get(SettingsLangResourceKind.FailClosed)),
        ];

        _loading = true;
        SelectedLanguage = Languages.FirstOrDefault(l => l.Value?.ToString() == Loc.Preference) ?? Languages[0];
        ThemeIndex = (int)appearance.Preference;
        LaunchAtLogin = autoStart.IsEnabled;
        _loading = false;
    }

    public override string Key => "settings";

    public bool CanModify => _connection.CanModify;

    public IReadOnlyList<Choice<LanguageTag?>> Languages { get; }

    public IReadOnlyList<Choice<Proto.VerdictAction>> DefaultActions { get; }

    public IReadOnlyList<Choice<Proto.FailPolicy>> FailPolicies { get; }

    public bool IsAutoStartSupported => _autoStart.IsSupported;

    [ObservableProperty]
    public partial Choice<LanguageTag?> SelectedLanguage { get; set; }

    /// <summary>0 跟隨系統，1 淺色，2 深色（與 <see cref="ThemePreference"/> 一致）。</summary>
    [ObservableProperty]
    public partial int ThemeIndex { get; set; }

    [ObservableProperty]
    public partial bool LaunchAtLogin { get; set; }

    [ObservableProperty]
    public partial decimal? PromptTimeoutSeconds { get; set; } = 30;

    [ObservableProperty]
    public partial Choice<Proto.VerdictAction>? DefaultAction { get; set; }

    [ObservableProperty]
    public partial Choice<Proto.FailPolicy>? FailPolicy { get; set; }

    [ObservableProperty]
    public partial bool AllowIcmp { get; set; } = true;

    [ObservableProperty]
    public partial bool BlockInbound { get; set; }

    [ObservableProperty]
    public partial decimal? LogRetentionDays { get; set; } = 30;

    public bool IsConnected => _connection.IsConnected;

    /// <summary>診斷包包含規則和應用列表（默認勾選：大多數問題與規則有關）。</summary>
    [ObservableProperty]
    public partial bool DiagnosticsIncludeConfiguration { get; set; } = true;

    /// <summary>診斷包包含連接日誌（涉及瀏覽記錄，默認不勾選）。</summary>
    [ObservableProperty]
    public partial bool DiagnosticsIncludeActivity { get; set; }

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(ExportDiagnosticsCommand))]
    public partial bool IsExportingDiagnostics { get; set; }

    private bool CanExportDiagnostics() => !IsExportingDiagnostics;

    [RelayCommand(CanExecute = nameof(CanExportDiagnostics))]
    private async Task ExportDiagnosticsAsync()
    {
        IsExportingDiagnostics = true;
        try
        {
            await _diagnostics.ExportAsync(new DiagnosticsExportOptions(DiagnosticsIncludeConfiguration, DiagnosticsIncludeActivity));
        }
        finally
        {
            IsExportingDiagnostics = false;
        }
    }

    [RelayCommand]
    private Task OpenLogFolderAsync() => _diagnostics.OpenLogFolderAsync();

    /// <summary>每日備份和恢復前快照，最新的在前。</summary>
    public ObservableCollection<Choice<Proto.BackupInfo>> Backups { get; } = [];

    /// <summary>配置文件列表（只在連上服務後加載）。</summary>
    public ObservableCollection<ProfileItemViewModel> Profiles { get; } = [];

    public bool HasProfiles => Profiles.Count > 0;

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(RestoreBackupCommand))]
    public partial Choice<Proto.BackupInfo>? SelectedBackup { get; set; }

    public bool HasBackups => Backups.Count > 0;

    [RelayCommand]
    private Task ExportRulesAsync() => _transfer.ExportAsync();

    [RelayCommand]
    private Task ImportRulesAsync() => _transfer.ImportAsync();

    private bool CanRestoreBackup() => SelectedBackup is not null;

    [RelayCommand(CanExecute = nameof(CanRestoreBackup))]
    private async Task RestoreBackupAsync()
    {
        if (SelectedBackup is { } backup && await _transfer.RestoreAsync(backup.Value.Name, backup.Title))
        {
            await LoadBackupsAsync(); // 恢復會新增一份恢復前快照
        }
    }

    private async Task LoadBackupsAsync()
    {
        var response = await _connection.RequireClients().Rules.ListBackupsAsync(new Empty());
        var selected = SelectedBackup?.Value.Name;
        Backups.Clear();
        foreach (var backup in response.Backups)
        {
            Backups.Add(new Choice<Proto.BackupInfo>(backup, () => BackupTitle(backup)));
        }

        SelectedBackup = Backups.FirstOrDefault(b => b.Value.Name == selected) ?? Backups.FirstOrDefault();
        OnPropertyChanged(nameof(HasBackups));
    }

    private async Task LoadProfilesAsync()
    {
        var response = await _connection.RequireClients().Profiles.ListAsync(new Empty());
        Profiles.Clear();
        foreach (var profile in response.Profiles)
        {
            Profiles.Add(new ProfileItemViewModel(profile, Text));
        }

        OnPropertyChanged(nameof(HasProfiles));
    }

    [RelayCommand]
    private Task AddProfileAsync() => RunAsync(async () =>
    {
        var editor = new ProfileEditorViewModel(Text, profiles: Profiles.Select(p => p.Info).ToArray());
        if (!await _dialogs.ShowFormAsync<ProfileEditorView, ProfileEditorViewModel>(editor, Loc.Get(ProfilesLangResourceKind.AddTitle)))
        {
            return;
        }

        await _connection.RequireClients().Profiles.CreateAsync(editor.ToCreateRequest());
        _feedback.Success(Loc.Get(ProfilesLangResourceKind.ProfileCreated));
        await LoadProfilesAsync();
    });

    [RelayCommand]
    private Task EditProfileAsync(ProfileItemViewModel item) => RunAsync(async () =>
    {
        var editor = new ProfileEditorViewModel(Text, item.Info, Profiles.Select(p => p.Info).ToArray());
        if (!await _dialogs.ShowFormAsync<ProfileEditorView, ProfileEditorViewModel>(editor, Loc.Get(ProfilesLangResourceKind.EditTitle)))
        {
            return;
        }

        await _connection.RequireClients().Profiles.UpdateAsync(editor.ToUpdateInfo());
        _feedback.Success(Loc.Get(ProfilesLangResourceKind.ProfileUpdated));
        await LoadProfilesAsync();
    });

    [RelayCommand]
    private Task ActivateProfileAsync(ProfileItemViewModel item) => RunAsync(async () =>
    {
        await _connection.RequireClients().Profiles.ActivateAsync(new Proto.ActivateProfileRequest { Id = item.Info.Id });
        _feedback.Success(Loc.Get(ProfilesLangResourceKind.ProfileActivated));
        await LoadProfilesAsync();
    });

    [RelayCommand]
    private Task DeleteProfileAsync(ProfileItemViewModel item) => RunAsync(async () =>
    {
        await _connection.RequireClients().Profiles.DeleteAsync(new Proto.DeleteProfileRequest { Id = item.Info.Id });
        _feedback.Success(Loc.Get(ProfilesLangResourceKind.ProfileDeleted));
        await LoadProfilesAsync();
    });

    private string BackupTitle(Proto.BackupInfo backup)
    {
        var time = backup.CreatedAt.ToDateTimeOffset().ToLocalTime().ToString("g", Loc.Culture);
        return Loc.Format(backup.IsRestoreSnapshot ? SettingsLangResourceKind.BackupSnapshot : SettingsLangResourceKind.BackupDaily, time);
    }

    public override Task OnActivatedAsync() => RunAsync(async () =>
    {
        await LoadProfilesAsync();
        await LoadBackupsAsync();
        _loaded = await _connection.RequireClients().Control.GetSettingsAsync(new Empty());
        PromptTimeoutSeconds = (decimal)_loaded.PromptTimeout.ToTimeSpan().TotalSeconds;
        DefaultAction = DefaultActions.FirstOrDefault(a => a.Value == _loaded.PromptDefaultAction) ?? DefaultActions[0];
        FailPolicy = FailPolicies.FirstOrDefault(p => p.Value == _loaded.FailPolicy) ?? FailPolicies[0];
        AllowIcmp = _loaded.AllowIcmpByDefault;
        BlockInbound = _loaded.InboundDefault == Proto.InboundDefault.Deny;
        LogRetentionDays = _loaded.LogRetentionDays;
        OnPropertyChanged(nameof(IsConnected));
    });

    [RelayCommand]
    private Task SaveAsync() => RunAsync(async () =>
    {
        var settings = _loaded?.Clone() ?? new Proto.UserSettings();
        settings.PromptTimeout = Duration.FromTimeSpan(TimeSpan.FromSeconds((double)(PromptTimeoutSeconds ?? 30)));
        settings.PromptDefaultAction = DefaultAction?.Value ?? Proto.VerdictAction.Deny;
        settings.FailPolicy = FailPolicy?.Value ?? Proto.FailPolicy.FailOpen;
        settings.AllowIcmpByDefault = AllowIcmp;
        settings.InboundDefault = BlockInbound ? Proto.InboundDefault.Deny : Proto.InboundDefault.Allow;
        settings.LogRetentionDays = (int)(LogRetentionDays ?? 30);

        _loaded = await _connection.RequireClients().Control.UpdateSettingsAsync(settings);
        _feedback.Success(Loc.Get(SettingsLangResourceKind.Saved));
    });

    partial void OnSelectedLanguageChanged(Choice<LanguageTag?> value)
    {
        if (!_loading)
        {
            Loc.SetPreference(value.Value);
        }
    }

    partial void OnThemeIndexChanged(int value)
    {
        if (!_loading && value is >= 0 and <= 2)
        {
            _ = _appearance.SetPreferenceAsync((ThemePreference)value);
        }
    }

    partial void OnLaunchAtLoginChanged(bool value)
    {
        if (!_loading)
        {
            _autoStart.SetEnabled(value);
        }
    }

    protected override void OnLanguageChanged()
    {
        foreach (var choice in Languages.Concat<Choice>(DefaultActions).Concat(FailPolicies).Concat(Backups))
        {
            choice.Refresh();
        }

        foreach (var profile in Profiles)
        {
            profile.Refresh();
        }

        base.OnLanguageChanged();
    }
}
