using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Traffic;
using NetworkControl.Storage.Activity;

namespace NetworkControl.Storage.Tests;

public sealed class TrafficRepositoryTests
{
    private static readonly DateTimeOffset Start = new(2026, 11, 14, 8, 0, 0, TimeSpan.Zero);

    private static CancellationToken Ct => TestContext.Current.CancellationToken;

    [Fact]
    public async Task Accumulates_deltas_and_summarizes_by_bucket()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var repository = new SqliteTrafficRepository(fx.Activity, new FakeTimeProvider(Start.AddHours(3)));

        await repository.AddAsync([new TrafficDelta(7, Start.AddMinutes(5), 1000, 100), new TrafficDelta(9, Start.AddMinutes(5), 50, 5)], Ct);
        await repository.AddAsync([new TrafficDelta(7, Start.AddMinutes(5), 500, 50), new TrafficDelta(7, Start.AddMinutes(70), 2000, 200)], Ct);

        var summary = await repository.SummarizeAsync(Start, Start.AddHours(3), TimeSpan.FromHours(1), topCount: 5, Ct);

        Assert.Equal(3550, summary.BytesIn);
        Assert.Equal(355, summary.BytesOut);
        Assert.Equal(3, summary.Timeline.Count);
        Assert.Equal(new TrafficBucket(Start, 1550, 155), summary.Timeline[0]);
        Assert.Equal(new TrafficBucket(Start.AddHours(1), 2000, 200), summary.Timeline[1]);
        Assert.Equal(new TrafficBucket(Start.AddHours(2), 0, 0), summary.Timeline[2]);
        Assert.Equal([7L, 9L], summary.TopApps.Select(a => a.AppId));
        Assert.Equal(3850, summary.TopApps[0].Total);

        var byApp = await repository.ByAppAsync(Start, Start.AddHours(3), Ct);
        Assert.Equal(new AppTraffic(9, 50, 5), byApp[9]);
    }

    [Fact]
    public async Task Long_ranges_use_the_hour_table()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var repository = new SqliteTrafficRepository(fx.Activity, new FakeTimeProvider(Start.AddDays(10)));
        await repository.AddAsync([new TrafficDelta(7, Start.AddMinutes(5), 10, 1), new TrafficDelta(7, Start.AddDays(2).AddMinutes(30), 20, 2)], Ct);

        var summary = await repository.SummarizeAsync(Start, Start.AddDays(7), TimeSpan.FromDays(1), topCount: 1, Ct);

        Assert.Equal(7, summary.Timeline.Count);
        Assert.Equal(10, summary.Timeline[0].BytesIn);
        Assert.Equal(20, summary.Timeline[2].BytesIn);
    }
}
