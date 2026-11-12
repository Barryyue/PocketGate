using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Rules;
using static NetworkControl.Core.Tests.TestData;

namespace NetworkControl.Core.Tests;

public class PromptRuleFactoryTests
{
    private static readonly AppIdentity Chrome = App(1, "/opt/google/chrome/chrome", signer: "Google LLC");
    private static readonly DomainCandidates Github = new(["api.github.com"]);
    private static readonly TimeSpan OnceFallback = TimeSpan.FromSeconds(60);

    private static Rule? Create(PromptDecision decision, FlowInfo? flow = null, AppIdentity? app = null, DomainCandidates? domains = null) =>
        PromptRuleFactory.CreateRule(decision, flow ?? Flow(), app ?? Chrome, domains ?? Github, Profile.DefaultId, T0, OnceFallback);

    [Fact]
    public void Once_on_pending_engine_creates_no_rule() =>
        Assert.Null(Create(new PromptDecision { Action = VerdictAction.Allow, Lifetime = RuleLifetime.Once }));

    [Fact]
    public void Once_on_blocked_event_becomes_short_timed_rule_for_ip_and_port()
    {
        var rule = Create(new PromptDecision { Action = VerdictAction.Allow, Lifetime = RuleLifetime.Once },
            Flow(origin: FlowOrigin.BlockedEvent));

        Assert.NotNull(rule);
        Assert.Equal(RuleLifetime.Timed, rule.Lifetime);
        Assert.Equal(T0 + OnceFallback, rule.ExpiresAt);
        Assert.IsType<AddressTarget>(rule.Conditions.Remote);
        Assert.Equal(PortRange.Single(443), rule.Conditions.RemotePort);
    }

    [Fact]
    public void Timed_rule_uses_selected_duration()
    {
        var rule = Create(new PromptDecision { Action = VerdictAction.Allow, Lifetime = RuleLifetime.Timed, Duration = TimeSpan.FromMinutes(5) });
        Assert.Equal(T0.AddMinutes(5), rule!.ExpiresAt);
    }

    [Fact]
    public void Until_exit_binds_process_or_degrades_to_until_restart()
    {
        var process = new ProcessRef(42, 7);
        var bound = Create(new PromptDecision { Action = VerdictAction.Allow, Lifetime = RuleLifetime.UntilProcessExit }, Flow(process: process));
        Assert.Equal(process, bound!.BoundProcess);

        var noProcess = Flow() with { Process = null };
        var degraded = Create(new PromptDecision { Action = VerdictAction.Allow, Lifetime = RuleLifetime.UntilProcessExit }, noProcess);
        Assert.Equal(RuleLifetime.UntilRestart, degraded!.Lifetime);
    }

    [Fact]
    public void Domain_scope_falls_back_to_address_without_domains()
    {
        var conditions = PromptRuleFactory.CreateConditions(
            new PromptDecision { Action = VerdictAction.Allow, Scope = PromptScope.Domain }, Flow(), DomainCandidates.None);
        Assert.IsType<AddressTarget>(conditions.Remote);
    }

    [Fact]
    public void Domain_scope_uses_selected_domain()
    {
        var rule = Create(new PromptDecision { Action = VerdictAction.Deny, Scope = PromptScope.Domain, SelectedDomain = "github.com" });
        Assert.Equal(new DomainTarget("github.com"), rule!.Conditions.Remote);
        Assert.Equal(Direction.Outbound, rule.Conditions.Direction);
        Assert.Equal("Google LLC", rule.App!.RequiredSignerId);
    }

    [Fact]
    public void Unknown_app_creates_no_rule() =>
        Assert.Null(PromptRuleFactory.CreateRule(
            new PromptDecision { Action = VerdictAction.Allow }, Flow(), null, Github, null, T0, OnceFallback));
}
