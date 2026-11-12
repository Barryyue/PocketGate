using System.Globalization;
using Google.Protobuf.WellKnownTypes;
using NetworkControl.App.Infrastructure;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Localization;

/// <summary>
/// 把 IPC 枚舉和值轉成當前語言的界面文本。所有面向用戶的枚舉文本都集中在這裡，
/// 調用方在語言切換後重新讀取即可得到新語言的文本。
/// </summary>
public sealed class DisplayText(LocalizationService loc)
{
    public LocalizationService Localization => loc;

    public string Mode(Proto.FilterMode mode) => loc.Get(mode switch
    {
        Proto.FilterMode.Whitelist => CommonLangResourceKind.ModeWhitelist,
        Proto.FilterMode.Blacklist => CommonLangResourceKind.ModeBlacklist,
        Proto.FilterMode.BlockAll => CommonLangResourceKind.ModeBlockAll,
        Proto.FilterMode.Disabled => CommonLangResourceKind.ModeDisabled,
        _ => CommonLangResourceKind.ModeAsk,
    });

    public string ModeDescription(Proto.FilterMode mode) => loc.Get(mode switch
    {
        Proto.FilterMode.Whitelist => CommonLangResourceKind.ModeWhitelistDescription,
        Proto.FilterMode.Blacklist => CommonLangResourceKind.ModeBlacklistDescription,
        Proto.FilterMode.BlockAll => CommonLangResourceKind.ModeBlockAllDescription,
        Proto.FilterMode.Disabled => CommonLangResourceKind.ModeDisabledDescription,
        _ => CommonLangResourceKind.ModeAskDescription,
    });

    public string Action(Proto.VerdictAction action) =>
        loc.Get(action == Proto.VerdictAction.Allow ? CommonLangResourceKind.Allow : CommonLangResourceKind.Deny);

    public string Direction(Proto.Direction direction) =>
        loc.Get(direction == Proto.Direction.Inbound ? CommonLangResourceKind.Inbound : CommonLangResourceKind.Outbound);

    public static string Protocol(Proto.Protocol protocol) => protocol switch
    {
        Proto.Protocol.Tcp => "TCP",
        Proto.Protocol.Udp => "UDP",
        Proto.Protocol.Icmp => "ICMP",
        Proto.Protocol.Icmpv6 => "ICMPv6",
        Proto.Protocol.Unspecified => "",
        _ => protocol.ToString().ToUpperInvariant(),
    };

    public string Zone(Proto.NetworkZone zone) => loc.Get(zone switch
    {
        Proto.NetworkZone.Loopback => CommonLangResourceKind.ZoneLoopback,
        Proto.NetworkZone.LocalNetwork => CommonLangResourceKind.ZoneLocalNetwork,
        _ => CommonLangResourceKind.ZoneInternet,
    });

    public string Lifetime(Proto.Lifetime lifetime, Timestamp? expiresAt = null) => lifetime switch
    {
        Proto.Lifetime.Once => loc.Get(CommonLangResourceKind.LifetimeOnce),
        Proto.Lifetime.UntilProcessExit => loc.Get(CommonLangResourceKind.LifetimeUntilExit),
        Proto.Lifetime.Timed when expiresAt is not null =>
            loc.Format(CommonLangResourceKind.ExpiresAt, expiresAt.ToDateTimeOffset().ToLocalTime().DateTime),
        Proto.Lifetime.Timed => loc.Get(CommonLangResourceKind.LifetimeTimed),
        Proto.Lifetime.UntilRestart => loc.Get(CommonLangResourceKind.LifetimeUntilRestart),
        _ => loc.Get(CommonLangResourceKind.LifetimePermanent),
    };

    public string Source(Proto.RuleSource source) => loc.Get(source switch
    {
        Proto.RuleSource.Prompt => CommonLangResourceKind.SourcePrompt,
        Proto.RuleSource.Builtin => CommonLangResourceKind.SourceBuiltin,
        Proto.RuleSource.Subscription => CommonLangResourceKind.SourceSubscription,
        Proto.RuleSource.Import => CommonLangResourceKind.SourceImport,
        _ => CommonLangResourceKind.SourceUser,
    });

    public string Reason(Proto.VerdictReason reason) => loc.Get(reason switch
    {
        Proto.VerdictReason.Mode => CommonLangResourceKind.ReasonMode,
        Proto.VerdictReason.Prompt => CommonLangResourceKind.ReasonPrompt,
        Proto.VerdictReason.PromptTimeout => CommonLangResourceKind.ReasonPromptTimeout,
        Proto.VerdictReason.Builtin => CommonLangResourceKind.ReasonBuiltin,
        Proto.VerdictReason.NoUserSession => CommonLangResourceKind.ReasonNoUserSession,
        Proto.VerdictReason.DoNotDisturb => CommonLangResourceKind.ReasonDoNotDisturb,
        Proto.VerdictReason.FailSafe => CommonLangResourceKind.ReasonFailSafe,
        Proto.VerdictReason.Cancelled => CommonLangResourceKind.ReasonCancelled,
        _ => CommonLangResourceKind.ReasonRule,
    });

    public string DateTime(Timestamp? timestamp, string format = "G") =>
        timestamp is null ? "" : timestamp.ToDateTimeOffset().ToLocalTime().ToString(format, loc.Culture);

    public static string Endpoint(Proto.IpEndpoint? endpoint)
    {
        if (endpoint is null)
        {
            return "";
        }

        var port = endpoint.Port.ToString(CultureInfo.InvariantCulture);
        // IPv6 地址加方括號，避免與端口混淆
        return endpoint.Address.Contains(':', StringComparison.Ordinal) ? $"[{endpoint.Address}]:{port}" : $"{endpoint.Address}:{port}";
    }

    /// <summary>規則條件的一行摘要，例如「輸出 · *.github.com · TCP 443」。</summary>
    public string Conditions(Proto.RuleConditions? c)
    {
        if (c is null)
        {
            return loc.Get(CommonLangResourceKind.AnyConnection);
        }

        var parts = new List<string>();
        if (c.HasDirection)
        {
            parts.Add(Direction(c.Direction));
        }

        switch (c.RemoteCase)
        {
            case Proto.RuleConditions.RemoteOneofCase.Domain:
                parts.Add(c.Domain);
                break;
            case Proto.RuleConditions.RemoteOneofCase.Addresses:
                parts.Add(string.Join(", ", c.Addresses.Networks));
                break;
            case Proto.RuleConditions.RemoteOneofCase.Zone:
                parts.Add(Zone(c.Zone));
                break;
        }

        var protocol = c.HasProtocol ? Protocol(c.Protocol) : "";
        var port = c.RemotePort is { } rp
            ? (rp.From == rp.To ? rp.From.ToString(CultureInfo.InvariantCulture) : $"{rp.From}-{rp.To}")
            : "";
        if (protocol.Length > 0 || port.Length > 0)
        {
            parts.Add($"{protocol} {port}".Trim());
        }

        var onlyDirection = parts.Count == 1 && c.HasDirection;
        return parts.Count == 0 || onlyDirection
            ? (onlyDirection ? $"{parts[0]} · {loc.Get(CommonLangResourceKind.AnyConnection)}" : loc.Get(CommonLangResourceKind.AnyConnection))
            : string.Join(" · ", parts);
    }

    public static string FileName(string? path)
    {
        if (string.IsNullOrEmpty(path))
        {
            return "";
        }

        var index = path.LastIndexOfAny(['\\', '/']);
        return index >= 0 ? path[(index + 1)..] : path;
    }
}
