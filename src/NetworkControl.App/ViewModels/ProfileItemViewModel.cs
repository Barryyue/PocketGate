using CommunityToolkit.Mvvm.ComponentModel;
using NetworkControl.App.Localization;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>設置頁配置文件列表中的一行。</summary>
public sealed class ProfileItemViewModel(Proto.ProfileInfo info, DisplayText text) : ObservableObject
{
    public Proto.ProfileInfo Info { get; } = info;

    public string Name => Info.Name;

    public string ModeText => text.Mode(Info.Mode);

    public string RuleCountText => text.Localization.Format(ProfilesLangResourceKind.RuleCount, Info.RuleCount);

    public bool IsActive => Info.IsActive;

    public bool IsDefault => Info.IsDefault;

    public bool CanDelete => !Info.IsDefault && !Info.IsActive;

    public bool CanActivate => !Info.IsActive;

    public void Refresh() => OnPropertyChanged(string.Empty);
}
