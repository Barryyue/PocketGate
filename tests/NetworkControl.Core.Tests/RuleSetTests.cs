using System.Net;
using NetworkControl.Core.Domain;
using static NetworkControl.Core.Tests.TestData;

namespace NetworkControl.Core.Tests;

public class RuleSetTests
{
    private static readonly AppIdentity Code = App(1, "/usr/share/code/code", signer: "Microsoft Corporation");

    [Fact]
    public void Returns_no_match_when_no_rules()
    {
        var match = RuleSetOf().Evaluate(Context(Flow(), Code));
        Assert.False(match.IsMatch);
    }

    [Fact]
    public void More_specific_rule_wins_US04()
    {
        // US-04：VS Code 只允許 *.github.com，其他一律拒絕
        var denyAll = Rule(VerdictAction.Deny, Matcher(Code.Path, signer: "Microsoft Corporation"));
        var allowGithub = Rule(VerdictAction.Allow, Matcher(Code.Path, signer: "Microsoft Corporation"),
            new RuleConditions { Remote = new DomainTarget("*.github.com") });
        var rules = RuleSetOf(denyAll, allowGithub);

        Assert.Equal(allowGithub.Id, rules.Evaluate(Context(Flow(), Code, "api.github.com")).Rule?.Id);
        Assert.Equal(denyAll.Id, rules.Evaluate(Context(Flow(), Code, "telemetry.example.com")).Rule?.Id);
    }

    [Fact]
    public void Deny_wins_when_specificity_is_equal()
    {
        var allow = Rule(VerdictAction.Allow, Matcher(Code.Path), updatedAt: T0.AddMinutes(5));
        var deny = Rule(VerdictAction.Deny, Matcher(Code.Path));
        Assert.Equal(deny.Id, RuleSetOf(allow, deny).Evaluate(Context(Flow(), Code)).Rule?.Id);
    }

    [Fact]
    public void Manual_priority_overrides_specificity()
    {
        var specific = Rule(VerdictAction.Deny, Matcher(Code.Path), new RuleConditions { RemotePort = PortRange.Single(443) });
        var pinned = Rule(VerdictAction.Allow, manualPriority: 10);
        Assert.Equal(pinned.Id, RuleSetOf(specific, pinned).Evaluate(Context(Flow(), Code)).Rule?.Id);
    }

    [Fact]
    public void App_specific_rule_beats_global_rule()
    {
        var globalDeny = Rule(VerdictAction.Deny, conditions: new RuleConditions { Remote = new ZoneTarget(NetworkZone.Internet) });
        var appAllow = Rule(VerdictAction.Allow, Matcher(Code.Path));
        Assert.Equal(appAllow.Id, RuleSetOf(globalDeny, appAllow).Evaluate(Context(Flow(), Code)).Rule?.Id);

        // 其他應用仍然受全局規則約束
        var other = App(2, "/usr/bin/curl");
        Assert.Equal(globalDeny.Id, RuleSetOf(globalDeny, appAllow).Evaluate(Context(Flow(), other)).Rule?.Id);
    }

    [Fact]
    public void Wildcard_app_rules_are_merged_in_priority_order()
    {
        var discord = App(3, "/opt/discord/app-0.0.80/Discord", signer: "Discord Inc.");
        var wildcardDeny = Rule(VerdictAction.Deny, Matcher("/opt/discord/app-*/Discord", signer: "Discord Inc."),
            new RuleConditions { RemotePort = PortRange.Single(443) });
        var exactAllow = Rule(VerdictAction.Allow, Matcher(discord.Path, signer: "Discord Inc."));

        // 帶端口條件的通配規則更具體，應排在精確路徑但無條件的規則之前
        Assert.Equal(wildcardDeny.Id, RuleSetOf(exactAllow, wildcardDeny).Evaluate(Context(Flow(port: 443), discord)).Rule?.Id);
        Assert.Equal(exactAllow.Id, RuleSetOf(exactAllow, wildcardDeny).Evaluate(Context(Flow(port: 80), discord)).Rule?.Id);
    }

    [Theory]
    [InlineData("140.82.112.3", true)]
    [InlineData("140.82.113.9", true)]
    [InlineData("8.8.8.8", false)]
    public void Address_targets_match_networks(string remote, bool expected)
    {
        var rule = Rule(VerdictAction.Allow, conditions: new RuleConditions
        {
            Remote = new AddressTarget([IPNetwork.Parse("140.82.112.0/20")]),
        });
        Assert.Equal(expected, RuleSetOf(rule).Evaluate(Context(Flow(remote), Code)).IsMatch);
    }

    [Fact]
    public void Protocol_direction_and_port_ranges_are_respected()
    {
        var rule = Rule(VerdictAction.Allow, conditions: new RuleConditions
        {
            Protocol = L4Protocol.Udp,
            Direction = Direction.Outbound,
            RemotePort = new PortRange(27000, 27050),
        });
        var rules = RuleSetOf(rule);

        Assert.True(rules.Evaluate(Context(Flow(port: 27015, protocol: L4Protocol.Udp), Code)).IsMatch);
        Assert.False(rules.Evaluate(Context(Flow(port: 27015, protocol: L4Protocol.Tcp), Code)).IsMatch);
        Assert.False(rules.Evaluate(Context(Flow(port: 28000, protocol: L4Protocol.Udp), Code)).IsMatch);
        Assert.False(rules.Evaluate(Context(Flow(port: 27015, protocol: L4Protocol.Udp, direction: Direction.Inbound), Code)).IsMatch);
    }

    [Fact]
    public void Tampered_app_skips_its_rules_and_reports_tamper()
    {
        // FR-APP-05：簽名者變化後，原規則不再生效
        var rule = Rule(VerdictAction.Allow, Matcher(Code.Path, signer: "Microsoft Corporation"));
        var replaced = Code with { Signature = new CodeSignature("Evil Corp") };

        var match = RuleSetOf(rule).Evaluate(Context(Flow(), replaced));

        Assert.False(match.IsMatch);
        Assert.Equal(rule.Id, match.TamperedRule?.Id);
    }

    [Fact]
    public void Unsigned_app_hash_change_is_tamper()
    {
        var tool = App(5, "/home/dev/tool", sha256: "aaaa");
        var rule = Rule(VerdictAction.Allow, Matcher(tool.Path, sha: "aaaa"));

        Assert.True(RuleSetOf(rule).Evaluate(Context(Flow(), tool)).IsMatch);
        Assert.Equal(rule.Id, RuleSetOf(rule).Evaluate(Context(Flow(), tool with { Sha256 = "bbbb" })).TamperedRule?.Id);
    }

    [Fact]
    public void Rule_insertion_order_does_not_affect_result()
    {
        var rules = new[]
        {
            Rule(VerdictAction.Deny, Matcher(Code.Path)),
            Rule(VerdictAction.Allow, Matcher(Code.Path), new RuleConditions { Remote = new DomainTarget("*.github.com") }),
            Rule(VerdictAction.Allow, conditions: new RuleConditions { RemotePort = PortRange.Single(443) }),
            Rule(VerdictAction.Deny, conditions: new RuleConditions { Remote = new ZoneTarget(NetworkZone.Internet) }),
        };
        var ctx = Context(Flow(), Code, "api.github.com");
        var expected = RuleSetOf(rules).Evaluate(ctx).Rule?.Id;

        var random = new Random(42);
        for (var i = 0; i < 20; i++)
        {
            var shuffled = rules.OrderBy(_ => random.Next()).ToArray();
            Assert.Equal(expected, RuleSetOf(shuffled).Evaluate(ctx).Rule?.Id);
        }
    }
}
