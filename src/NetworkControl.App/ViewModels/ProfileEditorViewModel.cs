using CommunityToolkit.Mvvm.ComponentModel;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>新建或編輯配置文件的對話框。</summary>
public sealed partial class ProfileEditorViewModel : LocalizedViewModel, IValidatingDialogContent
{
    private readonly Proto.ProfileInfo? _existing;

    public ProfileEditorViewModel(DisplayText text, Proto.ProfileInfo? existing = null, IReadOnlyList<Proto.ProfileInfo>? profiles = null)
        : base(text)
    {
        _existing = existing;
        Modes =
        [
            new Choice<Proto.FilterMode>(Proto.FilterMode.Ask, () => Loc.Get(CommonLangResourceKind.ModeAsk)),
            new Choice<Proto.FilterMode>(Proto.FilterMode.Whitelist, () => Loc.Get(CommonLangResourceKind.ModeWhitelist)),
            new Choice<Proto.FilterMode>(Proto.FilterMode.Blacklist, () => Loc.Get(CommonLangResourceKind.ModeBlacklist)),
        ];
        CopySources =
        [
            new Choice<string?>(null, () => Loc.Get(ProfilesLangResourceKind.CopyRulesNone)),
            .. (profiles ?? []).Select(p => new Choice<string?>(p.Id, () => p.Name)),
        ];
        NetworkKinds =
        [
            new Choice<Proto.NetworkKindValue?>(null, () => Loc.Get(ProfilesLangResourceKind.NetworkKindAny)),
            new Choice<Proto.NetworkKindValue?>(Proto.NetworkKindValue.Wired, () => Loc.Get(ProfilesLangResourceKind.NetworkKindWired)),
            new Choice<Proto.NetworkKindValue?>(Proto.NetworkKindValue.Wifi, () => Loc.Get(ProfilesLangResourceKind.NetworkKindWifi)),
            new Choice<Proto.NetworkKindValue?>(Proto.NetworkKindValue.Cellular, () => Loc.Get(ProfilesLangResourceKind.NetworkKindCellular)),
        ];

        if (existing is not null)
        {
            Name = existing.Name;
            SelectedMode = Modes.FirstOrDefault(m => m.Value == existing.Mode) ?? Modes[0];
            AutoActivateEnabled = existing.AutoSsids.Count > 0 || existing.HasAutoKind;
            SelectedNetworkKind = NetworkKinds.FirstOrDefault(k => existing.HasAutoKind && k.Value == existing.AutoKind) ?? NetworkKinds[0];
            Ssids = string.Join(", ", existing.AutoSsids);
        }
        else
        {
            SelectedMode = Modes[0];
        }

        SelectedCopySource = CopySources[0];
        SelectedNetworkKind ??= NetworkKinds[0];
    }

    public bool IsEditing => _existing is not null;

    public bool CanCopy => !IsEditing && CopySources.Count > 1;

    public IReadOnlyList<Choice<Proto.FilterMode>> Modes { get; }

    public IReadOnlyList<Choice<string?>> CopySources { get; }

    public IReadOnlyList<Choice<Proto.NetworkKindValue?>> NetworkKinds { get; }

    [ObservableProperty]
    public partial string Name { get; set; } = "";

    [ObservableProperty]
    public partial Choice<Proto.FilterMode> SelectedMode { get; set; }

    [ObservableProperty]
    public partial Choice<string?>? SelectedCopySource { get; set; }

    [ObservableProperty]
    public partial bool AutoActivateEnabled { get; set; }

    [ObservableProperty]
    public partial Choice<Proto.NetworkKindValue?>? SelectedNetworkKind { get; set; }

    [ObservableProperty]
    public partial string Ssids { get; set; } = "";

    [ObservableProperty]
    public partial string? ValidationError { get; set; }

    public bool Validate()
    {
        var trimmed = Name.Trim();
        ValidationError = trimmed.Length is 0 or > 40 ? Loc.Get(ProfilesLangResourceKind.NameInvalid) : null;
        return ValidationError is null;
    }

    public Proto.CreateProfileRequest ToCreateRequest()
    {
        var request = new Proto.CreateProfileRequest
        {
            Name = Name.Trim(),
            Mode = SelectedMode.Value,
        };
        if (SelectedCopySource?.Value is { } copyFrom)
        {
            request.CopyRulesFrom = copyFrom;
        }

        if (AutoActivateEnabled)
        {
            if (SelectedNetworkKind?.Value is { } kind)
            {
                request.AutoKind = kind;
            }

            foreach (var ssid in SplitSsids())
            {
                request.AutoSsids.Add(ssid);
            }
        }

        return request;
    }

    public Proto.ProfileInfo ToUpdateInfo()
    {
        var info = _existing?.Clone() ?? new Proto.ProfileInfo();
        info.Name = Name.Trim();
        info.Mode = SelectedMode.Value;
        info.AutoSsids.Clear();
        info.ClearAutoKind();
        if (AutoActivateEnabled)
        {
            if (SelectedNetworkKind?.Value is { } kind)
            {
                info.AutoKind = kind;
            }

            foreach (var ssid in SplitSsids())
            {
                info.AutoSsids.Add(ssid);
            }
        }

        return info;
    }

    private IEnumerable<string> SplitSsids() =>
        Ssids.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

    protected override void OnLanguageChanged()
    {
        foreach (var choice in Modes.Concat<Choice>(CopySources).Concat(NetworkKinds))
        {
            choice.Refresh();
        }

        base.OnLanguageChanged();
    }
}
