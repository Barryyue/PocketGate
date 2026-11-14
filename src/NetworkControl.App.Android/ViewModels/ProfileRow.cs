using CommunityToolkit.Mvvm.ComponentModel;
using NetworkControl.Core.Domain;

namespace NetworkControl.Android.ViewModels;

/// <summary>設定頁配置文件列表中的一行。</summary>
public sealed class ProfileRow(
    Guid id,
    string name,
    FilterMode mode,
    string modeText,
    string ruleCountText,
    bool isActive,
    bool isDefault) : ObservableObject
{
    public Guid Id { get; } = id;

    public string Name { get; } = name;

    public FilterMode Mode { get; } = mode;

    public string Summary => $"{modeText} · {ruleCountText}";

    public int ModeIndex => Mode switch
    {
        FilterMode.Whitelist => 1,
        FilterMode.Blacklist => 2,
        _ => 0,
    };

    public bool IsActive { get; } = isActive;

    public bool IsDefault { get; } = isDefault;

    public bool CanActivate => !IsActive;

    public bool CanDelete => !IsActive && !IsDefault;

    public void Refresh() => OnPropertyChanged(string.Empty);
}
