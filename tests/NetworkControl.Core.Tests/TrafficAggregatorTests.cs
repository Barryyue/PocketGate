using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Traffic;

namespace NetworkControl.Core.Tests;

public sealed class TrafficAggregatorTests
{
    private static readonly DateTimeOffset T0 = new(2026, 11, 14, 8, 0, 10, TimeSpan.Zero);

    [Fact]
    public async Task Resolves_apps_and_merges_samples_per_minute()
    {
        var repository = new RecordingRepository();
        var aggregator = new TrafficAggregator(new PathResolver(), [repository], new FakeTimeProvider(T0), NullLogger<TrafficAggregator>.Instance);

        aggregator.Record(
        [
            new TrafficSample(null, "/usr/bin/curl", 100, 10, T0),
            new TrafficSample(null, "/usr/bin/curl", 50, 5, T0.AddSeconds(30)),
            new TrafficSample(null, "/usr/bin/curl", 1, 1, T0.AddMinutes(1)),
            new TrafficSample(null, null, 7, 0, T0),
            new TrafficSample(null, "/usr/bin/curl", 0, 0, T0), // 空樣本被忽略
        ]);
        await aggregator.FlushAsync(TestContext.Current.CancellationToken);

        var minute = new DateTimeOffset(2026, 11, 14, 8, 0, 0, TimeSpan.Zero);
        Assert.Equal(3, repository.Deltas.Count);
        Assert.Contains(new TrafficDelta(42, minute, 150, 15), repository.Deltas);
        Assert.Contains(new TrafficDelta(42, minute.AddMinutes(1), 1, 1), repository.Deltas);
        Assert.Contains(new TrafficDelta(-1, minute, 7, 0), repository.Deltas);
    }

    [Fact]
    public async Task Works_without_a_repository()
    {
        var aggregator = new TrafficAggregator(new PathResolver(), [], new FakeTimeProvider(T0), NullLogger<TrafficAggregator>.Instance);
        aggregator.Record([new TrafficSample(null, "/usr/bin/curl", 1, 1, T0)]);

        await aggregator.FlushAsync(TestContext.Current.CancellationToken);
    }

    private sealed class PathResolver : IAppIdentityResolver
    {
        public ValueTask<AppIdentity?> ResolveAsync(FlowInfo flow, CancellationToken cancellationToken) =>
            ValueTask.FromResult<AppIdentity?>(flow.ExecutablePath is { } path ? new AppIdentity { AppId = 42, Path = path } : null);
    }

    private sealed class RecordingRepository : ITrafficRepository
    {
        public List<TrafficDelta> Deltas { get; } = [];

        public Task AddAsync(IReadOnlyCollection<TrafficDelta> deltas, CancellationToken cancellationToken)
        {
            Deltas.AddRange(deltas);
            return Task.CompletedTask;
        }

        public Task<TrafficSummary> SummarizeAsync(DateTimeOffset from, DateTimeOffset to, TimeSpan bucket, int topCount, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<IReadOnlyDictionary<long, AppTraffic>> ByAppAsync(DateTimeOffset from, DateTimeOffset to, CancellationToken cancellationToken) =>
            throw new NotSupportedException();
    }
}
