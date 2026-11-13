using System.Net;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Rules;
using NetworkControl.Engine.Vpn.Platform;
using NetworkControl.Engine.Vpn.Routing;
using static NetworkControl.Engine.Vpn.Tests.TestApps;

namespace NetworkControl.Engine.Vpn.Tests;

public class VpnRoutingPlannerTests
{
    private static readonly InstalledApp[] AllApps = [Self, Chrome, Firefox, Telegram, SystemSettings, SystemShell];

    private static VpnRoutingPlan Plan(FilterMode mode, params Rule[] rules) => Plan(mode, AllApps, rules);

    private static VpnRoutingPlan Plan(FilterMode mode, InstalledApp[] apps, params Rule[] rules) =>
        VpnRoutingPlanner.Plan(new VpnRoutingInput { Rules = RuleSetOf(rules), Mode = mode, Apps = apps, SelfPackage = SelfPackage });

    private static AppRoutingDecision DecisionOf(VpnRoutingPlan plan, InstalledApp app) => plan.Find(app.Uid)!.Decision;

    [Fact]
    public void Ask_mode_blocks_and_asks_for_apps_without_rules()
    {
        var plan = Plan(FilterMode.Ask);

        Assert.Equal(AppRoutingDecision.BlockAndAsk, DecisionOf(plan, Chrome));
        Assert.Equal(AppRoutingDecision.BlockAndAsk, DecisionOf(plan, Firefox));
        Assert.DoesNotContain(Chrome.PackageName, plan.BypassPackages);
    }

    [Fact]
    public void Self_and_system_uids_always_bypass()
    {
        foreach (var mode in Enum.GetValues<FilterMode>())
        {
            var plan = Plan(mode, Rule(VerdictAction.Deny));

            Assert.Equal(AppRoutingReason.Self, plan.Find(Self.Uid)!.Reason);
            Assert.Equal(AppRoutingReason.SystemUid, plan.Find(SystemSettings.Uid)!.Reason);
            Assert.Contains(SelfPackage, plan.BypassPackages);
            Assert.Contains("android", plan.BypassPackages);
            Assert.Contains("com.android.settings", plan.BypassPackages);
        }
    }

    [Fact]
    public void Self_package_is_bypassed_even_if_catalog_does_not_list_it()
    {
        var plan = Plan(FilterMode.BlockAll, [Chrome]);
        Assert.Contains(SelfPackage, plan.BypassPackages);
    }

    [Fact]
    public void Secondary_user_system_uid_is_detected()
    {
        Assert.True(VpnRoutingPlanner.IsSystemUid(1000));
        Assert.True(VpnRoutingPlanner.IsSystemUid(1_001_000)); // 用戶 10 的 system UID
        Assert.False(VpnRoutingPlanner.IsSystemUid(10_100));
        Assert.False(VpnRoutingPlanner.IsSystemUid(1_010_100));
    }

    [Fact]
    public void App_rules_decide_routing()
    {
        var allowChrome = Rule(VerdictAction.Allow, MatcherFor(Chrome));
        var denyFirefox = Rule(VerdictAction.Deny, MatcherFor(Firefox));
        var plan = Plan(FilterMode.Ask, allowChrome, denyFirefox);

        Assert.Equal(AppRoutingDecision.Bypass, DecisionOf(plan, Chrome));
        Assert.Equal(allowChrome.Id, plan.Find(Chrome.Uid)!.RuleId);
        Assert.Equal(AppRoutingDecision.Block, DecisionOf(plan, Firefox));
        Assert.Equal(AppRoutingDecision.BlockAndAsk, DecisionOf(plan, Telegram));
        Assert.Contains(Chrome.PackageName, plan.BypassPackages);
        Assert.DoesNotContain(Firefox.PackageName, plan.BypassPackages);
    }

    [Theory]
    [InlineData(FilterMode.Whitelist, AppRoutingDecision.Block)]
    [InlineData(FilterMode.Blacklist, AppRoutingDecision.Bypass)]
    [InlineData(FilterMode.Ask, AppRoutingDecision.BlockAndAsk)]
    [InlineData(FilterMode.BlockAll, AppRoutingDecision.Block)]
    [InlineData(FilterMode.Disabled, AppRoutingDecision.Bypass)]
    public void Mode_decides_apps_without_rules(FilterMode mode, AppRoutingDecision expected)
    {
        Assert.Equal(expected, DecisionOf(Plan(mode), Telegram));
    }

    [Fact]
    public void Block_all_and_disabled_ignore_rules()
    {
        var allowChrome = Rule(VerdictAction.Allow, MatcherFor(Chrome));
        var denyFirefox = Rule(VerdictAction.Deny, MatcherFor(Firefox));

        Assert.Equal(AppRoutingDecision.Block, DecisionOf(Plan(FilterMode.BlockAll, allowChrome), Chrome));
        Assert.Equal(AppRoutingDecision.Bypass, DecisionOf(Plan(FilterMode.Disabled, denyFirefox), Firefox));
    }

    [Fact]
    public void Global_rule_applies_to_all_apps_and_app_rule_wins()
    {
        var denyAll = Rule(VerdictAction.Deny);
        var allowChrome = Rule(VerdictAction.Allow, MatcherFor(Chrome));
        var plan = Plan(FilterMode.Blacklist, denyAll, allowChrome);

        Assert.Equal(AppRoutingDecision.Bypass, DecisionOf(plan, Chrome));
        Assert.Equal(AppRoutingDecision.Block, DecisionOf(plan, Firefox));
        Assert.Equal(denyAll.Id, plan.Find(Firefox.Uid)!.RuleId);
    }

    [Fact]
    public void Manual_priority_is_respected()
    {
        var allowChrome = Rule(VerdictAction.Allow, MatcherFor(Chrome));
        var pinnedDenyAll = Rule(VerdictAction.Deny, priority: 10);
        Assert.Equal(AppRoutingDecision.Block, DecisionOf(Plan(FilterMode.Ask, allowChrome, pinnedDenyAll), Chrome));
    }

    [Fact]
    public void Rules_with_connection_conditions_are_skipped_and_reported()
    {
        var allowDomain = Rule(VerdictAction.Allow, MatcherFor(Chrome), new RuleConditions { Remote = new DomainTarget("*.google.com") });
        var allowPort = Rule(VerdictAction.Allow, MatcherFor(Firefox), new RuleConditions { RemotePort = PortRange.Single(443) });
        var denyIp = Rule(VerdictAction.Deny, conditions: new RuleConditions { Remote = AddressTarget.Single(IPAddress.Parse("1.1.1.1")) });
        var inbound = Rule(VerdictAction.Allow, MatcherFor(Telegram), new RuleConditions { Direction = Direction.Inbound });
        var plan = Plan(FilterMode.Ask, allowDomain, allowPort, denyIp, inbound);

        // 這些規則不影響分流
        Assert.Equal(AppRoutingDecision.BlockAndAsk, DecisionOf(plan, Chrome));
        Assert.Equal(AppRoutingDecision.BlockAndAsk, DecisionOf(plan, Firefox));
        Assert.Equal(AppRoutingDecision.BlockAndAsk, DecisionOf(plan, Telegram));

        Assert.Equal(4, plan.SkippedRules.Count);
        Assert.Contains(plan.SkippedRules, s => s.RuleId == inbound.Id && s.Reason == RuleSkipReason.Inbound);
        Assert.Contains(plan.SkippedRules, s => s.RuleId == allowDomain.Id && s.Reason == RuleSkipReason.UnsupportedConditions);
    }

    [Fact]
    public void Rule_without_direction_is_enforceable()
    {
        var allowAnyDirection = Rule(VerdictAction.Allow, MatcherFor(Chrome), RuleConditions.Any);
        Assert.Equal(AppRoutingDecision.Bypass, DecisionOf(Plan(FilterMode.Ask, allowAnyDirection), Chrome));
    }

    [Fact]
    public void Tampered_app_is_not_bypassed_even_in_blacklist_mode()
    {
        // 規則是為另一個簽名者生成的：同包名但簽名不同（可能是重新打包的應用）
        var allowChrome = Rule(VerdictAction.Allow, MatcherFor(Chrome, signer: "original-signer"));

        var ask = Plan(FilterMode.Ask, allowChrome);
        Assert.Equal(AppRoutingDecision.BlockAndAsk, DecisionOf(ask, Chrome));
        Assert.Equal(allowChrome.Id, ask.Find(Chrome.Uid)!.TamperedRuleId);

        Assert.Equal(AppRoutingDecision.BlockAndAsk, DecisionOf(Plan(FilterMode.Blacklist, allowChrome), Chrome));
        Assert.Equal(AppRoutingDecision.Block, DecisionOf(Plan(FilterMode.Whitelist, allowChrome), Chrome));
    }

    [Fact]
    public void Shared_uid_uses_first_package_and_bypasses_all_packages()
    {
        var a = App("com.example.b", 10_200, signer: "s");
        var b = App("com.example.a", 10_200, signer: "s");
        var allowA = Rule(VerdictAction.Allow, MatcherFor(b));
        var plan = Plan(FilterMode.Ask, [a, b], allowA);

        var routing = plan.Find(10_200)!;
        Assert.Equal("com.example.a", routing.PackageName);
        Assert.Equal(["com.example.a", "com.example.b"], routing.Packages);
        Assert.Contains("com.example.a", plan.BypassPackages);
        Assert.Contains("com.example.b", plan.BypassPackages);
    }

    [Fact]
    public void Wildcard_package_rule_matches()
    {
        var allowMozilla = Rule(VerdictAction.Allow, new AppMatcher { PathPattern = "android-app://org.mozilla.*" });
        var plan = Plan(FilterMode.Whitelist, allowMozilla);

        Assert.Equal(AppRoutingDecision.Bypass, DecisionOf(plan, Firefox));
        Assert.Equal(AppRoutingDecision.Block, DecisionOf(plan, Chrome));
    }

    [Fact]
    public void Tunnel_config_equivalence_ignores_set_order()
    {
        var a = new VpnTunnelConfig(new HashSet<string>(StringComparer.Ordinal) { "a", "b" });
        var b = new VpnTunnelConfig(new HashSet<string>(StringComparer.Ordinal) { "b", "a" });
        var c = new VpnTunnelConfig(new HashSet<string>(StringComparer.Ordinal) { "a" });

        Assert.True(a.IsEquivalentTo(b));
        Assert.False(a.IsEquivalentTo(c));
        Assert.False(a.IsEquivalentTo(null));
    }
}
