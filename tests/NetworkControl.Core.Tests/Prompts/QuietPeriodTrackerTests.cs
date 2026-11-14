using Microsoft.Extensions.DependencyInjection;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Tests.Scenarios;

namespace NetworkControl.Core.Tests.Prompts;

public sealed class QuietPeriodTrackerTests
{
    [Fact]
    public async Task Aggregates_do_not_disturb_events_and_finishes_when_disabled()
    {
        await using var host = await ScenarioHost.StartAsync(withUiSession: false);
        var ct = TestContext.Current.CancellationToken;
        var tracker = host.Services.GetRequiredService<QuietPeriodTracker>();
        var now = host.Time.GetUtcNow();

        await host.State.SetDoNotDisturbAsync(now.AddHours(1), ct);
        tracker.Consume(Event(now, VerdictAction.Deny));
        tracker.Consume(Event(now.AddSeconds(1), VerdictAction.Deny));
        tracker.Consume(Event(now.AddSeconds(2), VerdictAction.Allow));
        tracker.Consume(Event(now, VerdictAction.Deny, VerdictReason.Rule));

        await host.State.SetDoNotDisturbAsync(null, ct);

        var summary = Assert.IsType<QuietPeriodSummary>(tracker.LastSummary);
        var app = Assert.Single(summary.Apps);
        Assert.Equal(2, app.Denied);
        Assert.Equal(1, app.Allowed);
        Assert.Equal(2, summary.Denied);
        Assert.Equal(1, summary.Allowed);

        tracker.Dismiss();
        Assert.Null(tracker.LastSummary);
    }

    [Fact]
    public async Task No_summary_is_created_when_nothing_was_blocked()
    {
        await using var host = await ScenarioHost.StartAsync(withUiSession: false);
        var ct = TestContext.Current.CancellationToken;
        var tracker = host.Services.GetRequiredService<QuietPeriodTracker>();

        await host.State.SetDoNotDisturbAsync(host.Time.GetUtcNow().AddHours(1), ct);
        await host.State.SetDoNotDisturbAsync(null, ct);

        Assert.Null(tracker.LastSummary);
    }

    private static ConnectionEvent Event(DateTimeOffset at, VerdictAction action, VerdictReason reason = VerdictReason.DoNotDisturb) => new(
        at,
        7,
        "/opt/example",
        "Example",
        Direction.Outbound,
        L4Protocol.Tcp,
        new System.Net.IPEndPoint(System.Net.IPAddress.Parse("192.168.1.10"), 50000),
        new System.Net.IPEndPoint(System.Net.IPAddress.Parse("203.0.113.10"), 443),
        "example.com",
        new Verdict(action, reason));
}
