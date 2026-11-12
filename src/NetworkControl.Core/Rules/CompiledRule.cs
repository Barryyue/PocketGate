using System.Net;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;

namespace NetworkControl.Core.Rules;

/// <summary>
/// 預編譯的規則：模式解析、網段解析都在構造時完成，求值時只做比較。
/// </summary>
internal sealed class CompiledRule
{
    private readonly PathPattern? _path;
    private readonly DomainPattern? _domain;
    private readonly IPNetwork[]? _networks;
    private readonly NetworkZone? _zone;

    public CompiledRule(Rule rule)
    {
        Rule = rule;
        Specificity = RuleSpecificity.Compute(rule);

        if (rule.App is { } app)
        {
            _path = PathPattern.Parse(app.PathPattern);
        }

        switch (rule.Conditions.Remote)
        {
            case DomainTarget d:
                _domain = DomainPattern.Parse(d.Pattern);
                break;
            case AddressTarget a:
                _networks = [.. a.Networks];
                break;
            case ZoneTarget z:
                _zone = z.Zone;
                break;
        }
    }

    public Rule Rule { get; }

    public int Specificity { get; }

    /// <summary>在規則集全局優先級順序中的位置，越小越優先。</summary>
    public int Rank { get; set; }

    /// <summary>無通配符的應用路徑，用於精確索引。</summary>
    public string? ExactPathKey => _path?.Literal;

    public bool IsGlobal => Rule.App is null;

    public AppMatchOutcome MatchApp(AppIdentity? app)
    {
        var matcher = Rule.App;
        if (matcher is null)
        {
            return AppMatchOutcome.Match;
        }

        if (app is null || !_path!.Matches(app.Path))
        {
            return AppMatchOutcome.NoMatch;
        }

        if (matcher.ServiceName is not null && !string.Equals(matcher.ServiceName, app.ServiceName, StringComparison.OrdinalIgnoreCase))
        {
            return AppMatchOutcome.NoMatch;
        }

        if (matcher.PackageId is not null && !string.Equals(matcher.PackageId, app.PackageId, StringComparison.OrdinalIgnoreCase))
        {
            return AppMatchOutcome.NoMatch;
        }

        if (matcher.RequiredSignerId is not null && !string.Equals(matcher.RequiredSignerId, app.Signature?.SignerId, StringComparison.Ordinal))
        {
            return AppMatchOutcome.Tampered;
        }

        if (matcher.RequiredSha256 is not null && app.Sha256 is not null
            && !string.Equals(matcher.RequiredSha256, app.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            return AppMatchOutcome.Tampered;
        }

        return AppMatchOutcome.Match;
    }

    public bool MatchConditions(in EvaluationContext ctx)
    {
        var c = Rule.Conditions;
        var flow = ctx.Flow;

        if (c.Direction is { } direction && direction != flow.Direction)
        {
            return false;
        }

        if (c.Protocol is { } protocol && protocol != flow.Protocol)
        {
            return false;
        }

        if (c.RemotePort is { } remotePort && !remotePort.Contains(flow.Remote.Port))
        {
            return false;
        }

        if (c.LocalPort is { } localPort && !localPort.Contains(flow.Local.Port))
        {
            return false;
        }

        if (_domain is not null)
        {
            var matched = false;
            foreach (var name in ctx.Domains.Names)
            {
                if (_domain.Matches(name))
                {
                    matched = true;
                    break;
                }
            }

            if (!matched)
            {
                return false;
            }
        }

        if (_networks is not null)
        {
            var matched = false;
            foreach (var network in _networks)
            {
                if (NetworkZones.Contains(network, flow.Remote.Address))
                {
                    matched = true;
                    break;
                }
            }

            if (!matched)
            {
                return false;
            }
        }

        return _zone is not { } zone || zone == ctx.RemoteZone;
    }
}
