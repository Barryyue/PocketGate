using AtomUI.Localization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Google.Protobuf.WellKnownTypes;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using NetworkControl.App.Services;
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
    private Proto.UserSettings? _loaded;
    private bool _loading;

    public SettingsViewModel(
        ServiceConnection connection,
        AppearanceService appearance,
        AutoStartService autoStart,
        FeedbackService feedback,
        DiagnosticsExportFlow diagnostics,
        DisplayText text)
        : base(text)
    {
        _diagnostics = diagnostics;
        _connection = connection;
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

    public override Task OnActivatedAsync() => RunAsync(async () =>
    {
        _loaded = await _connection.RequireClients().Control.GetSettingsAsync(new Empty());
        PromptTimeoutSeconds = (decimal)_loaded.PromptTimeout.ToTimeSpan().TotalSeconds;
        DefaultAction = DefaultActions.FirstOrDefault(a => a.Value == _loaded.PromptDefaultAction) ?? DefaultActions[0];
        FailPolicy = FailPolicies.FirstOrDefault(p => p.Value == _loaded.FailPolicy) ?? FailPolicies[0];
        AllowIcmp = _loaded.AllowIcmpByDefault;
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
        foreach (var choice in Languages.Concat<Choice>(DefaultActions).Concat(FailPolicies))
        {
            choice.Refresh();
        }

        base.OnLanguageChanged();
    }
}
