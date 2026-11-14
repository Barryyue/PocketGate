using System.Globalization;
using System.Net;
using CommunityToolkit.Mvvm.ComponentModel;
using Google.Protobuf.WellKnownTypes;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

public enum RemoteKind
{
    Any,
    Domain,
    Address,
    Zone,
}

/// <summary>新建規則時的預填值（例如從日誌或阻斷通知創建規則）。</summary>
public sealed record RulePrefill(
    string? AppPath = null,
    Proto.VerdictAction Action = Proto.VerdictAction.Allow,
    string? Domain = null,
    string? Address = null,
    uint? Port = null,
    Proto.Protocol Protocol = Proto.Protocol.Unspecified,
    Proto.Direction? Direction = null);

/// <summary>
/// 規則編輯表單。手動規則只支持「永久 / 直到重啟 / 限時」三種時效（「僅本次」和「直到應用退出」只能在彈窗中選擇）。
/// 編輯已有規則時，表單沒有暴露的字段（簽名要求、服務名、配置文件、手動優先級等）原樣保留。
/// </summary>
public sealed partial class RuleEditorViewModel : LocalizedViewModel, IValidatingDialogContent
{
    private readonly Proto.RuleDefinition? _original;

    public RuleEditorViewModel(DisplayText text, Proto.RuleInfo? existing = null, RulePrefill? prefill = null, IReadOnlyList<Proto.ProfileInfo>? profiles = null)
        : base(text)
    {
        _original = existing?.Definition;
        IsBuiltin = existing?.Source == Proto.RuleSource.Builtin;

        Actions = [Option(Proto.VerdictAction.Allow, CommonLangResourceKind.Allow), Option(Proto.VerdictAction.Deny, CommonLangResourceKind.Deny)];
        Directions =
        [
            Option<Proto.Direction?>(null, CommonLangResourceKind.AnyDirection),
            Option<Proto.Direction?>(Proto.Direction.Outbound, CommonLangResourceKind.Outbound),
            Option<Proto.Direction?>(Proto.Direction.Inbound, CommonLangResourceKind.Inbound),
        ];
        Protocols =
        [
            Option(Proto.Protocol.Unspecified, CommonLangResourceKind.AnyProtocol),
            new Choice<Proto.Protocol>(Proto.Protocol.Tcp, () => "TCP"),
            new Choice<Proto.Protocol>(Proto.Protocol.Udp, () => "UDP"),
            new Choice<Proto.Protocol>(Proto.Protocol.Icmp, () => "ICMP"),
        ];
        RemoteKinds =
        [
            Option(RemoteKind.Any, RulesLangResourceKind.EditorRemoteAny),
            Option(RemoteKind.Domain, RulesLangResourceKind.EditorRemoteDomain),
            Option(RemoteKind.Address, RulesLangResourceKind.EditorRemoteAddress),
            Option(RemoteKind.Zone, RulesLangResourceKind.EditorRemoteZone),
        ];
        Zones =
        [
            Option(Proto.NetworkZone.Internet, CommonLangResourceKind.ZoneInternet),
            Option(Proto.NetworkZone.LocalNetwork, CommonLangResourceKind.ZoneLocalNetwork),
            Option(Proto.NetworkZone.Loopback, CommonLangResourceKind.ZoneLoopback),
        ];
        Lifetimes =
        [
            Option(Proto.Lifetime.Permanent, CommonLangResourceKind.LifetimePermanent),
            Option(Proto.Lifetime.UntilRestart, CommonLangResourceKind.LifetimeUntilRestart),
            Option(Proto.Lifetime.Timed, CommonLangResourceKind.LifetimeTimed),
        ];
        Profiles =
        [
            new Choice<string?>(null, () => Loc.Get(RulesLangResourceKind.ProfileFilterAll)),
            new Choice<string?>("", () => Loc.Get(RulesLangResourceKind.ProfileFilterShared)),
            .. (profiles ?? []).Select(p => new Choice<string?>(p.Id, () => p.Name)),
        ];

        SelectedAction = Actions[0];
        SelectedDirection = Directions[1];
        SelectedProtocol = Protocols[0];
        SelectedRemoteKind = RemoteKinds[0];
        SelectedZone = Zones[0];
        SelectedLifetime = Lifetimes[0];
        SelectedProfile = Profiles[0];

        if (_original is not null)
        {
            Load(_original);
            SelectedProfile = Profiles.FirstOrDefault(p => p.Value == _original.ProfileId) ?? Profiles[0];
        }
        else if (prefill is not null)
        {
            Load(prefill);
        }
    }

    public bool IsEditing => _original is not null;

    /// <summary>內置規則只能啟用 / 停用。</summary>
    public bool IsBuiltin { get; }

    public bool IsEditable => !IsBuiltin;

    public IReadOnlyList<Choice<Proto.VerdictAction>> Actions { get; }

    public IReadOnlyList<Choice<Proto.Direction?>> Directions { get; }

    public IReadOnlyList<Choice<Proto.Protocol>> Protocols { get; }

    public IReadOnlyList<Choice<RemoteKind>> RemoteKinds { get; }

    public IReadOnlyList<Choice<Proto.NetworkZone>> Zones { get; }

    public IReadOnlyList<Choice<Proto.Lifetime>> Lifetimes { get; }

    public IReadOnlyList<Choice<string?>> Profiles { get; }

    [ObservableProperty]
    public partial bool AnyApp { get; set; }

    [ObservableProperty]
    public partial string AppPath { get; set; } = "";

    [ObservableProperty]
    public partial Choice<Proto.VerdictAction> SelectedAction { get; set; }

    [ObservableProperty]
    public partial Choice<Proto.Direction?> SelectedDirection { get; set; }

    [ObservableProperty]
    public partial Choice<Proto.Protocol> SelectedProtocol { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsDomain), nameof(IsAddress), nameof(IsZone))]
    public partial Choice<RemoteKind> SelectedRemoteKind { get; set; }

    [ObservableProperty]
    public partial string Domain { get; set; } = "";

    [ObservableProperty]
    public partial string Addresses { get; set; } = "";

    [ObservableProperty]
    public partial Choice<Proto.NetworkZone> SelectedZone { get; set; }

    [ObservableProperty]
    public partial string Port { get; set; } = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsTimed))]
    public partial Choice<Proto.Lifetime> SelectedLifetime { get; set; }

    [ObservableProperty]
    public partial decimal? ExpiresInMinutes { get; set; } = 60;

    [ObservableProperty]
    public partial Choice<string?> SelectedProfile { get; set; }

    [ObservableProperty]
    public partial string Note { get; set; } = "";

    [ObservableProperty]
    public partial bool Enabled { get; set; } = true;

    [ObservableProperty]
    public partial string? ValidationError { get; set; }

    public bool IsDomain => SelectedRemoteKind.Value == RemoteKind.Domain;

    public bool IsAddress => SelectedRemoteKind.Value == RemoteKind.Address;

    public bool IsZone => SelectedRemoteKind.Value == RemoteKind.Zone;

    public bool IsTimed => SelectedLifetime.Value == Proto.Lifetime.Timed;

    public bool Validate()
    {
        ValidationError = FindError();
        return ValidationError is null;
    }

    /// <summary>生成要提交的規則定義。調用前必須先通過 <see cref="Validate"/>。</summary>
    public Proto.RuleDefinition ToDefinition()
    {
        var definition = _original?.Clone() ?? new Proto.RuleDefinition();
        if (IsBuiltin)
        {
            definition.Enabled = Enabled;
            return definition;
        }

        if (SelectedProfile.Value is { Length: > 0 } profileId)
        {
            definition.ProfileId = profileId;
        }
        else
        {
            definition.ClearProfileId();
        }

        definition.Action = SelectedAction.Value;
        definition.Lifetime = SelectedLifetime.Value;
        definition.ExpiresAt = IsTimed ? Timestamp.FromDateTimeOffset(DateTimeOffset.UtcNow.AddMinutes((double)(ExpiresInMinutes ?? 60))) : null;
        definition.Enabled = Enabled;
        if (string.IsNullOrWhiteSpace(Note))
        {
            definition.ClearNote();
        }
        else
        {
            definition.Note = Note.Trim();
        }

        if (AnyApp)
        {
            definition.App = null;
        }
        else
        {
            var path = AppPath.Trim();
            // 路徑改變後，原來綁定的簽名 / 哈希要求不再適用
            var keepIntegrity = definition.App is not null && string.Equals(definition.App.PathPattern, path, StringComparison.OrdinalIgnoreCase);
            definition.App = keepIntegrity ? definition.App!.Clone() : new Proto.AppMatcher();
            definition.App.PathPattern = path;
        }

        var conditions = new Proto.RuleConditions();
        if (SelectedDirection.Value is { } direction)
        {
            conditions.Direction = direction;
        }

        if (SelectedProtocol.Value != Proto.Protocol.Unspecified)
        {
            conditions.Protocol = SelectedProtocol.Value;
        }

        switch (SelectedRemoteKind.Value)
        {
            case RemoteKind.Domain:
                conditions.Domain = Domain.Trim();
                break;
            case RemoteKind.Address:
                conditions.Addresses = new Proto.AddressList();
                conditions.Addresses.Networks.AddRange(SplitAddresses(Addresses));
                break;
            case RemoteKind.Zone:
                conditions.Zone = SelectedZone.Value;
                break;
        }

        if (TryParsePort(Port, out var range) && range is not null)
        {
            conditions.RemotePort = range;
        }

        definition.Conditions = conditions;
        return definition;
    }

    protected override void OnLanguageChanged()
    {
        foreach (var choice in Actions.Concat<Choice>(Directions).Concat(Protocols).Concat(RemoteKinds).Concat(Zones).Concat(Lifetimes).Concat(Profiles))
        {
            choice.Refresh();
        }

        if (ValidationError is not null)
        {
            ValidationError = FindError();
        }

        base.OnLanguageChanged();
    }

    private string? FindError()
    {
        if (IsBuiltin)
        {
            return null;
        }

        if (!AnyApp && string.IsNullOrWhiteSpace(AppPath))
        {
            return Loc.Get(RulesLangResourceKind.ErrorAppPathRequired);
        }

        if (IsDomain && string.IsNullOrWhiteSpace(Domain))
        {
            return Loc.Get(RulesLangResourceKind.ErrorDomainRequired);
        }

        if (IsAddress)
        {
            var items = SplitAddresses(Addresses).ToList();
            if (items.Count == 0)
            {
                return Loc.Format(RulesLangResourceKind.ErrorAddressInvalid, "");
            }

            if (items.FirstOrDefault(a => !IPNetwork.TryParse(a, out _) && !IPAddress.TryParse(a, out _)) is { } bad)
            {
                return Loc.Format(RulesLangResourceKind.ErrorAddressInvalid, bad);
            }
        }

        if (!TryParsePort(Port, out _))
        {
            return Loc.Get(RulesLangResourceKind.ErrorPortInvalid);
        }

        return IsTimed && (ExpiresInMinutes is null || ExpiresInMinutes < 1) ? Loc.Get(RulesLangResourceKind.ErrorExpiryInvalid) : null;
    }

    private void Load(Proto.RuleDefinition d)
    {
        AnyApp = d.App is null;
        AppPath = d.App?.PathPattern ?? "";
        SelectedAction = Actions.First(a => a.Value == d.Action);
        Enabled = d.Enabled;
        Note = d.HasNote ? d.Note : "";
        SelectedLifetime = Lifetimes.FirstOrDefault(l => l.Value == d.Lifetime) ?? Lifetimes[0];
        if (d.ExpiresAt is { } expires)
        {
            ExpiresInMinutes = Math.Max(1, (decimal)Math.Ceiling((expires.ToDateTimeOffset() - DateTimeOffset.UtcNow).TotalMinutes));
        }

        var c = d.Conditions ?? new Proto.RuleConditions();
        SelectedDirection = Directions.First(x => x.Value == (c.HasDirection ? c.Direction : null));
        SelectedProtocol = Protocols.FirstOrDefault(p => p.Value == (c.HasProtocol ? c.Protocol : Proto.Protocol.Unspecified)) ?? Protocols[0];
        switch (c.RemoteCase)
        {
            case Proto.RuleConditions.RemoteOneofCase.Domain:
                SelectedRemoteKind = RemoteKinds[1];
                Domain = c.Domain;
                break;
            case Proto.RuleConditions.RemoteOneofCase.Addresses:
                SelectedRemoteKind = RemoteKinds[2];
                Addresses = string.Join(", ", c.Addresses.Networks);
                break;
            case Proto.RuleConditions.RemoteOneofCase.Zone:
                SelectedRemoteKind = RemoteKinds[3];
                SelectedZone = Zones.First(z => z.Value == c.Zone);
                break;
        }

        Port = c.RemotePort is { } rp ? (rp.From == rp.To ? rp.From.ToString(CultureInfo.InvariantCulture) : $"{rp.From}-{rp.To}") : "";
    }

    private void Load(RulePrefill p)
    {
        AnyApp = p.AppPath is null;
        AppPath = p.AppPath ?? "";
        SelectedAction = Actions.First(a => a.Value == p.Action);
        SelectedDirection = Directions.First(x => x.Value == p.Direction);
        SelectedProtocol = Protocols.FirstOrDefault(x => x.Value == p.Protocol) ?? Protocols[0];
        if (!string.IsNullOrEmpty(p.Domain))
        {
            SelectedRemoteKind = RemoteKinds[1];
            Domain = p.Domain;
        }
        else if (!string.IsNullOrEmpty(p.Address))
        {
            SelectedRemoteKind = RemoteKinds[2];
            Addresses = p.Address;
        }

        Port = p.Port?.ToString(CultureInfo.InvariantCulture) ?? "";
    }

    private static IEnumerable<string> SplitAddresses(string text) =>
        text.Split([',', ';', ' ', '\n', '\r', '\t'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

    /// <summary>空字符串表示任意端口；否則為單個端口或「起-止」範圍。</summary>
    private static bool TryParsePort(string text, out Proto.PortRange? range)
    {
        range = null;
        var value = text.Trim();
        if (value.Length == 0)
        {
            return true;
        }

        var parts = value.Split('-', StringSplitOptions.TrimEntries);
        if (parts.Length is 0 or > 2
            || !ushort.TryParse(parts[0], NumberStyles.None, CultureInfo.InvariantCulture, out var from)
            || from == 0)
        {
            return false;
        }

        var to = from;
        if (parts.Length == 2 && (!ushort.TryParse(parts[1], NumberStyles.None, CultureInfo.InvariantCulture, out to) || to < from))
        {
            return false;
        }

        range = new Proto.PortRange { From = from, To = to };
        return true;
    }

    private Choice<T> Option<T, TKind>(T value, TKind key)
        where TKind : struct, System.Enum => new(value, () => Loc.Get(key));

    private Choice<T> Option<T>(T value, CommonLangResourceKind key) => Option<T, CommonLangResourceKind>(value, key);

    private Choice<T> Option<T>(T value, RulesLangResourceKind key) => Option<T, RulesLangResourceKind>(value, key);
}
