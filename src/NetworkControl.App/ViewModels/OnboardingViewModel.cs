using CommunityToolkit.Mvvm.ComponentModel;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>首次運行引導（NFR-UX-01）：解釋彈窗機制，選擇初始模式，可選登錄時啟動。</summary>
public sealed partial class OnboardingViewModel(DisplayText text, bool isConnected, bool autoStartSupported, bool launchAtLogin)
    : LocalizedViewModel(text), IValidatingDialogContent
{
    private static readonly Proto.FilterMode[] Modes = [Proto.FilterMode.Ask, Proto.FilterMode.Whitelist, Proto.FilterMode.Blacklist];

    /// <summary>0 詢問（推薦），1 白名單，2 黑名單。</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ModeDescription))]
    public partial int ModeIndex { get; set; }

    [ObservableProperty]
    public partial bool LaunchAtLogin { get; set; } = launchAtLogin;

    public bool IsConnected { get; } = isConnected;

    public bool IsAutoStartSupported { get; } = autoStartSupported;

    public Proto.FilterMode SelectedMode => Modes[Math.Clamp(ModeIndex, 0, Modes.Length - 1)];

    public string AskTitle => $"{Text.Mode(Proto.FilterMode.Ask)}（{Loc.Get(OnboardingLangResourceKind.Recommended)}）";

    public string ModeDescription => Text.ModeDescription(SelectedMode);

    public bool Validate() => true;
}
