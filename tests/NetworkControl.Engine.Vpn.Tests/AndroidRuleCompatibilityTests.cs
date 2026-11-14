using NetworkControl.Core.Domain;
using NetworkControl.Engine.Vpn.Routing;
using static NetworkControl.Engine.Vpn.Tests.TestApps;

namespace NetworkControl.Engine.Vpn.Tests;

/// <summary>導入預覽的「Android 上不生效」統計（純函數）。</summary>
public class AndroidRuleCompatibilityTests
{
    private static Rule WithPath(string path, VerdictAction action = VerdictAction.Deny) =>
        Rule(action, new AppMatcher { PathPattern = path });

    [Fact]
    public void Android_package_rules_are_effective()
    {
        Assert.True(AndroidRuleCompatibility.IsEffectiveOnAndroid(Rule(VerdictAction.Deny, MatcherFor(Chrome))));
        Assert.True(AndroidRuleCompatibility.IsEffectiveOnAndroid(WithPath("android-app://com.android.*")));
        Assert.True(AndroidRuleCompatibility.IsEffectiveOnAndroid(Rule(VerdictAction.Allow)));
    }

    [Theory]
    [InlineData(@"C:\Program Files\Google\Chrome\Application\chrome.exe")]
    [InlineData(@"*chrome*")]
    [InlineData("com.android.chrome")]
    public void Desktop_and_plain_paths_are_not_effective(string path) =>
        Assert.Equal(AndroidRuleIneffectiveReason.NotAndroidApp, AndroidRuleCompatibility.GetIneffectiveReason(WithPath(path)));

    [Fact]
    public void Unsupported_conditions_are_reported()
    {
        var rule = Rule(VerdictAction.Deny, MatcherFor(Chrome), new RuleConditions { RemotePort = PortRange.Single(443) });
        Assert.Equal(AndroidRuleIneffectiveReason.UnsupportedConditions, AndroidRuleCompatibility.GetIneffectiveReason(rule));

        var domain = Rule(VerdictAction.Deny, MatcherFor(Chrome), new RuleConditions { Remote = new DomainTarget("example.com") });
        Assert.Equal(AndroidRuleIneffectiveReason.UnsupportedConditions, AndroidRuleCompatibility.GetIneffectiveReason(domain));
    }

    [Fact]
    public void Inbound_rules_are_reported()
    {
        var rule = Rule(VerdictAction.Deny, MatcherFor(Chrome), new RuleConditions { Direction = Direction.Inbound });
        Assert.Equal(AndroidRuleIneffectiveReason.Inbound, AndroidRuleCompatibility.GetIneffectiveReason(rule));
    }

    [Fact]
    public void Counts_only_ineffective_rules()
    {
        Rule[] rules =
        [
            Rule(VerdictAction.Deny, MatcherFor(Chrome)),
            WithPath(@"C:\Windows\notepad.exe"),
            Rule(VerdictAction.Deny, MatcherFor(Chrome), new RuleConditions { Direction = Direction.Inbound }),
            Rule(VerdictAction.Allow),
        ];

        Assert.Equal(2, AndroidRuleCompatibility.CountIneffective(rules));
    }
}
