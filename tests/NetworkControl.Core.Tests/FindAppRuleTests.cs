using NetworkControl.Core.Domain;
using static NetworkControl.Core.Tests.TestData;

namespace NetworkControl.Core.Tests;

/// <summary><see cref="Rules.RuleSet.FindAppRule"/>：只按應用分流的引擎（Android VPN）使用。</summary>
public class FindAppRuleTests
{
    private const string ChromePath = "android-app://com.android.chrome";
    private static readonly AppIdentity Chrome = App(1, ChromePath, signer: "aa11");

    private static bool AppLevelOnly(Rule rule) => rule.Conditions.Remote is null && rule.Conditions.RemotePort is null;

    [Fact]
    public void Returns_highest_priority_app_rule_passing_filter()
    {
        var conditional = Rule(VerdictAction.Allow, Matcher(ChromePath), new RuleConditions { RemotePort = PortRange.Single(443) });
        var appDeny = Rule(VerdictAction.Deny, Matcher(ChromePath));
        var rules = RuleSetOf(conditional, appDeny);

        // 更具體的條件規則被篩選掉，返回應用級規則
        Assert.Equal(appDeny.Id, rules.FindAppRule(Chrome, AppLevelOnly).Rule?.Id);
        // 不篩選時更具體的規則優先
        Assert.Equal(conditional.Id, rules.FindAppRule(Chrome, _ => true).Rule?.Id);
    }

    [Fact]
    public void Global_rule_applies_when_no_app_rule()
    {
        var global = Rule(VerdictAction.Deny);
        Assert.Equal(global.Id, RuleSetOf(global).FindAppRule(Chrome, AppLevelOnly).Rule?.Id);
    }

    [Fact]
    public void App_rule_beats_global_rule()
    {
        var global = Rule(VerdictAction.Deny);
        var allow = Rule(VerdictAction.Allow, Matcher(ChromePath));
        Assert.Equal(allow.Id, RuleSetOf(global, allow).FindAppRule(Chrome, AppLevelOnly).Rule?.Id);
    }

    [Fact]
    public void Tampered_rule_is_skipped_and_reported()
    {
        var allow = Rule(VerdictAction.Allow, Matcher(ChromePath, signer: "bb22"));
        var match = RuleSetOf(allow).FindAppRule(Chrome, AppLevelOnly);

        Assert.False(match.IsMatch);
        Assert.Equal(allow.Id, match.TamperedRule?.Id);
    }

    [Fact]
    public void Wildcard_rule_matches_packages()
    {
        var allow = Rule(VerdictAction.Allow, Matcher("android-app://com.android.*"));
        Assert.Equal(allow.Id, RuleSetOf(allow).FindAppRule(Chrome, AppLevelOnly).Rule?.Id);
        Assert.False(RuleSetOf(allow).FindAppRule(App(2, "android-app://org.mozilla.firefox"), AppLevelOnly).IsMatch);
    }
}
