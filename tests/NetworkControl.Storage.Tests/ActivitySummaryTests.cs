using System.Net;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Settings;
using NetworkControl.Storage.Activity;
using NetworkControl.Storage.Repositories;

namespace NetworkControl.Storage.Tests;

public sealed class ActivitySummaryTests
{
    private static readonly DateTimeOffset Start = new(2026, 11, 12, 8, 0, 0, TimeSpan.Zero);

    private static CancellationToken Ct => TestContext.Current.CancellationToken;

    [Fact]
    public async Task Summarizes_timeline_totals_and_rankings()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var time = new FakeTimeProvider(Start);
        var writer = new ActivityLogWriter(fx.Activity, new SettingsService(new SqliteSettingsRepository(fx.Config)), fx.Options, time, NullLogger<ActivityLogWriter>.Instance);
        await writer.StartAsync(Ct);

        // 08:05 curl → github 允許 3 次；08:10 curl → github 阻斷 1 次；09:30 未知應用 → 1.2.3.4 阻斷 2 次；10:15 browser → example 允許 1 次
        for (var i = 0; i < 3; i++)
        {
            writer.Consume(Event(Start.AddMinutes(5).AddSeconds(i), 7, "/usr/bin/curl", "curl", "api.github.com", "140.82.112.3", VerdictAction.Allow));
        }

        writer.Consume(Event(Start.AddMinutes(10), 7, "/usr/bin/curl", "curl", "api.github.com", "140.82.112.3", VerdictAction.Deny));
        writer.Consume(Event(Start.AddMinutes(90), null, "/tmp/unknown", null, null, "1.2.3.4", VerdictAction.Deny));
        writer.Consume(Event(Start.AddMinutes(91), null, "/tmp/unknown", null, null, "1.2.3.4", VerdictAction.Deny));
        writer.Consume(Event(Start.AddMinutes(135), 9, "/usr/bin/browser", "Browser", "example.com", "93.184.216.34", VerdictAction.Allow));
        // 範圍外：不應計入
        writer.Consume(Event(Start.AddHours(5), 9, "/usr/bin/browser", "Browser", "example.com", "93.184.216.34", VerdictAction.Allow));
        await writer.StopAsync(Ct);

        var summary = await new SqliteActivityQuery(fx.Activity).SummarizeAsync(
            new ActivitySummaryQuery { From = Start, To = Start.AddHours(3), BucketSize = TimeSpan.FromHours(1), TopCount = 2 },
            Ct);

        Assert.Equal(4, summary.Allowed);
        Assert.Equal(3, summary.Blocked);
        Assert.Equal(3, summary.AppCount);
        Assert.Equal(3, summary.DestinationCount);

        Assert.Equal(3, summary.Timeline.Count);
        Assert.Equal(new ActivityBucket(Start, 3, 1), summary.Timeline[0]);
        Assert.Equal(new ActivityBucket(Start.AddHours(1), 0, 2), summary.Timeline[1]);
        Assert.Equal(new ActivityBucket(Start.AddHours(2), 1, 0), summary.Timeline[2]);

        Assert.Equal(2, summary.TopApps.Count);
        Assert.Equal(new ActivityTopItem("app:7", "curl", 7, 3, 1, "/usr/bin/curl"), summary.TopApps[0]);
        Assert.Equal(new ActivityTopItem("path:/tmp/unknown", "/tmp/unknown", null, 0, 2, "/tmp/unknown"), summary.TopApps[1]);

        Assert.Equal("api.github.com", summary.TopDestinations[0].Key);
        Assert.Equal(4, summary.TopDestinations[0].Total);
        Assert.Equal("1.2.3.4", summary.TopDestinations[1].Label);
    }

    [Fact]
    public async Task Empty_range_returns_zero_filled_timeline()
    {
        await using var fx = await StorageFixture.CreateAsync();

        var summary = await new SqliteActivityQuery(fx.Activity).SummarizeAsync(
            new ActivitySummaryQuery { From = Start, To = Start.AddDays(1), BucketSize = TimeSpan.FromHours(1) },
            Ct);

        Assert.Equal(24, summary.Timeline.Count);
        Assert.All(summary.Timeline, bucket => Assert.Equal(0, bucket.Allowed + bucket.Blocked));
        Assert.Empty(summary.TopApps);
        Assert.Equal(0, summary.AppCount);
    }

    private static ConnectionEvent Event(DateTimeOffset at, long? appId, string path, string? name, string? host, string ip, VerdictAction action) => new(
        at,
        appId,
        path,
        name,
        Direction.Outbound,
        L4Protocol.Tcp,
        new IPEndPoint(IPAddress.Parse("192.168.1.10"), 50000),
        new IPEndPoint(IPAddress.Parse(ip), 443),
        host,
        new Verdict(action, VerdictReason.Rule));
}
