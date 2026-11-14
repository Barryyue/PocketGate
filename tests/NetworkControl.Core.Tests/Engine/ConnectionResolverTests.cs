using System.Net;
using Microsoft.Extensions.Options;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;

namespace NetworkControl.Core.Tests.Engine;

public sealed class ConnectionResolverTests
{
    private static readonly DateTimeOffset Now = new(2026, 11, 14, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public async Task Filters_listening_and_loopback_and_resolves_identity_and_hostname()
    {
        var processes = new FakeProcesses(new Dictionary<int, ProcessSnapshot>
        {
            [42] = new(new ProcessRef(42, 100), @"C:\Apps\Test.exe"),
        });
        var identities = new FakeResolver(new AppIdentity
        {
            AppId = 7,
            Path = @"C:\Apps\Test.exe",
            DisplayName = "Test",
        });
        var time = new FakeTimeProvider(Now);
        var dns = new DnsMapper(time, Options.Create(new DnsMapperOptions()));
        dns.Observe(new DnsObservation("api.example.com", [IPAddress.Parse("203.0.113.10")], TimeSpan.FromMinutes(5)));

        var connections = new[]
        {
            new ActiveConnection(
                L4Protocol.Tcp,
                new IPEndPoint(IPAddress.Parse("192.168.1.10"), 50000),
                new IPEndPoint(IPAddress.Parse("203.0.113.10"), 443),
                ConnectionState.Established,
                42),
            new ActiveConnection(
                L4Protocol.Tcp,
                new IPEndPoint(IPAddress.Any, 80),
                null,
                ConnectionState.Listen,
                42),
            new ActiveConnection(
                L4Protocol.Tcp,
                new IPEndPoint(IPAddress.Loopback, 50001),
                new IPEndPoint(IPAddress.Loopback, 8080),
                ConnectionState.Established,
                42),
        };

        var result = await ConnectionResolver.ResolveAsync(
            connections,
            processes,
            identities,
            dns,
            includeListening: false,
            includeLoopback: false,
            maxConnections: 10,
            Now,
            TestContext.Current.CancellationToken);

        var entry = Assert.Single(result);
        Assert.Equal(7, entry.App?.AppId);
        Assert.Equal("Test", entry.App?.DisplayName);
        Assert.Equal(@"C:\Apps\Test.exe", entry.Process?.ExecutablePath);
        Assert.Equal("api.example.com", entry.Hostname);
    }

    [Fact]
    public async Task Include_flags_keep_listening_and_loopback_entries()
    {
        var connections = new[]
        {
            new ActiveConnection(
                L4Protocol.Tcp,
                new IPEndPoint(IPAddress.Any, 80),
                null,
                ConnectionState.Listen,
                1),
            new ActiveConnection(
                L4Protocol.Tcp,
                new IPEndPoint(IPAddress.Loopback, 50001),
                new IPEndPoint(IPAddress.Loopback, 8080),
                ConnectionState.Established,
                2),
        };

        var result = await ConnectionResolver.ResolveAsync(
            connections,
            processes: null,
            identities: null,
            dns: null,
            includeListening: true,
            includeLoopback: true,
            maxConnections: 10,
            Now,
            TestContext.Current.CancellationToken);

        Assert.Equal(2, result.Count);
        Assert.All(result, entry => Assert.Null(entry.App));
    }

    [Fact]
    public async Task Stops_at_max_connections()
    {
        var connections = Enumerable.Range(0, 5)
            .Select(index => new ActiveConnection(
                L4Protocol.Tcp,
                new IPEndPoint(IPAddress.Parse("192.168.1.10"), 50000 + index),
                new IPEndPoint(IPAddress.Parse("203.0.113.10"), 443),
                ConnectionState.Established,
                index))
            .ToArray();

        var result = await ConnectionResolver.ResolveAsync(
            connections,
            processes: null,
            identities: null,
            dns: null,
            includeListening: false,
            includeLoopback: false,
            maxConnections: 2,
            Now,
            TestContext.Current.CancellationToken);

        Assert.Equal(2, result.Count);
    }

    [Theory]
    [InlineData("127.0.0.1", "203.0.113.10", true)]
    [InlineData("192.168.1.10", "127.0.0.1", true)]
    [InlineData("::1", "2001:db8::1", true)]
    [InlineData("192.168.1.10", "203.0.113.10", false)]
    public void Detects_loopback_on_either_end(string local, string remote, bool expected)
    {
        var connection = new ActiveConnection(
            L4Protocol.Tcp,
            new IPEndPoint(IPAddress.Parse(local), 50000),
            new IPEndPoint(IPAddress.Parse(remote), 443),
            ConnectionState.Established,
            1);

        Assert.Equal(expected, ConnectionResolver.IsLoopback(connection));
    }

    private sealed class FakeProcesses(IReadOnlyDictionary<int, ProcessSnapshot> snapshots) : IProcessInfoProvider
    {
        public bool TryGetProcess(int pid, out ProcessSnapshot snapshot) => snapshots.TryGetValue(pid, out snapshot!);

        public bool IsAlive(ProcessRef process) => false;
    }

    private sealed class FakeResolver(AppIdentity? app) : IAppIdentityResolver
    {
        public ValueTask<AppIdentity?> ResolveAsync(FlowInfo flow, CancellationToken cancellationToken) =>
            ValueTask.FromResult(app);
    }
}
