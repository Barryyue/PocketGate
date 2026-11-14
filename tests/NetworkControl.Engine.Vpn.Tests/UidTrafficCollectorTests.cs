using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Traffic;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Vpn.Tests;

public sealed class UidTrafficCollectorTests
{
    private static readonly DateTimeOffset Bucket = TestApps.T0;

    [Fact]
    public async Task Reports_only_growth_of_open_buckets_and_survives_restart()
    {
        var state = Path.Combine(Path.GetTempPath(), "nc-uid-traffic-" + Guid.NewGuid().ToString("N"), "state.json");
        try
        {
            var source = new FakeSource();
            var repository = new RecordingRepository();
            var time = new FakeTimeProvider(Bucket.AddMinutes(30));

            source.Buckets = [new(TestApps.Chrome.Uid, Bucket, Bucket.AddHours(2), 1000, 100), new(TestApps.Firefox.Uid, Bucket, Bucket.AddHours(2), 500, 50)];
            var (collector, aggregator) = Create(source, repository, time, state);
            Assert.Equal(2, collector.CollectNow());

            // 同一分桶繼續增長：只上報增量；Firefox 沒有變化不上報
            source.Buckets = [new(TestApps.Chrome.Uid, Bucket, Bucket.AddHours(2), 1500, 120), new(TestApps.Firefox.Uid, Bucket, Bucket.AddHours(2), 500, 50)];
            Assert.Equal(1, collector.CollectNow());
            await aggregator.FlushAsync(TestContext.Current.CancellationToken);

            Assert.Equal(1500, repository.Total(TestApps.Chrome).In);
            Assert.Equal(120, repository.Total(TestApps.Chrome).Out);
            Assert.Equal(500, repository.Total(TestApps.Firefox).In);

            // 進程重啟：從文件恢復已看到的值，不重複計數
            var (restarted, restartedAggregator) = Create(source, repository, time, state);
            Assert.Equal(0, restarted.CollectNow());
            source.Buckets = [new(TestApps.Chrome.Uid, Bucket, Bucket.AddHours(2), 1600, 120)];
            Assert.Equal(1, restarted.CollectNow());
            await restartedAggregator.FlushAsync(TestContext.Current.CancellationToken);
            Assert.Equal(1600, repository.Total(TestApps.Chrome).In);
        }
        finally
        {
            Directory.Delete(Path.GetDirectoryName(state)!, recursive: true);
        }
    }

    [Fact]
    public void Does_nothing_without_permission()
    {
        var source = new FakeSource { IsAvailable = false, Buckets = [new(TestApps.Chrome.Uid, Bucket, Bucket.AddHours(1), 1, 1)] };
        var (collector, _) = Create(source, new RecordingRepository(), new FakeTimeProvider(Bucket), statePath: null);

        Assert.Equal(0, collector.CollectNow());
    }

    private static (UidTrafficCollector Collector, TrafficAggregator Aggregator) Create(FakeSource source, RecordingRepository repository, TimeProvider time, string? statePath)
    {
        var aggregator = new TrafficAggregator(new PathResolver(), [repository], time, NullLogger<TrafficAggregator>.Instance);
        var collector = new UidTrafficCollector(source, new FakeAppCatalog(TestApps.Chrome, TestApps.Firefox), aggregator, time, NullLogger<UidTrafficCollector>.Instance, statePath);
        return (collector, aggregator);
    }

    private sealed class FakeSource : IUidTrafficSource
    {
        public bool IsAvailable { get; set; } = true;

        public IReadOnlyList<UidTrafficBucket> Buckets { get; set; } = [];

        public IReadOnlyList<UidTrafficBucket> Query(DateTimeOffset from, DateTimeOffset to) => IsAvailable ? Buckets : [];
    }

    /// <summary>把合成路徑映射為按 UID 區分的應用 ID。</summary>
    private sealed class PathResolver : IAppIdentityResolver
    {
        public ValueTask<AppIdentity?> ResolveAsync(FlowInfo flow, CancellationToken cancellationToken) =>
            ValueTask.FromResult<AppIdentity?>(flow.ExecutablePath is { } path
                ? new AppIdentity { AppId = path.GetHashCode(StringComparison.Ordinal) & 0x7FFFFFFF, Path = path }
                : null);
    }

    private sealed class RecordingRepository : ITrafficRepository
    {
        private readonly List<TrafficDelta> _deltas = [];

        public (long In, long Out) Total(InstalledApp app)
        {
            var id = PackageAppPath.ForPackage(app.PackageName).GetHashCode(StringComparison.Ordinal) & 0x7FFFFFFF;
            var rows = _deltas.Where(d => d.AppId == id).ToArray();
            return (rows.Sum(d => d.BytesIn), rows.Sum(d => d.BytesOut));
        }

        public Task AddAsync(IReadOnlyCollection<TrafficDelta> deltas, CancellationToken cancellationToken)
        {
            _deltas.AddRange(deltas);
            return Task.CompletedTask;
        }

        public Task<TrafficSummary> SummarizeAsync(DateTimeOffset from, DateTimeOffset to, TimeSpan bucket, int topCount, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<IReadOnlyDictionary<long, AppTraffic>> ByAppAsync(DateTimeOffset from, DateTimeOffset to, CancellationToken cancellationToken) =>
            throw new NotSupportedException();
    }
}
