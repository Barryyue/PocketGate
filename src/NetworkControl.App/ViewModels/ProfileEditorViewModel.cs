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

        if (existing is not null)
        {
            Name = existing.Name;
            SelectedMode = Modes.FirstOrDefault(m => m.Value == existing.Mode) ?? Modes[0];
        }
        else
        {
            SelectedMode = Modes[0];
        }

        SelectedCopySource = CopySources[0];
    }

    public bool IsEditing => _existing is not null;

    public bool CanCopy => !IsEditing && CopySources.Count > 1;

    public IReadOnlyList<Choice<Proto.FilterMode>> Modes { get; }

    public IReadOnlyList<Choice<string?>> CopySources { get; }

    [ObservableProperty]
    public partial string Name { get; set; } = "";

    [ObservableProperty]
    public partial Choice<Proto.FilterMode> SelectedMode { get; set; }

    [ObservableProperty]
    public partial Choice<string?>? SelectedCopySource { get; set; }

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

        return request;
    }

    public Proto.ProfileInfo ToUpdateInfo()
    {
        var info = _existing?.Clone() ?? new Proto.ProfileInfo();
        info.Name = Name.Trim();
        info.Mode = SelectedMode.Value;
        return info;
    }

    protected override void OnLanguageChanged()
    {
        foreach (var choice in Modes.Concat<Choice>(CopySources))
        {
            choice.Refresh();
        }

        base.OnLanguageChanged();
    }
}
