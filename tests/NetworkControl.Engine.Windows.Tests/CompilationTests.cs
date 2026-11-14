using System.Net;
using System.Net.Sockets;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;
using NetworkControl.Engine.Windows.Compilation;
using NetworkControl.Engine.Windows.Dns;
using NetworkControl.Engine.Windows.Platform;

namespace NetworkControl.Engine.Windows.Tests;

public class RuleCompilerTests
{
    private static readonly DateTimeOffset T0 = new(2026, 11, 12, 8, 0, 0, TimeSpan.Zero);

    private static Rule NewRule(VerdictAction action, string? app = null, RuleConditions? conditions = null) => new()
    {
        Id = Guid.NewGuid(),
        App = app is null ? null : new AppMatcher { PathPattern = app },
        Conditions = conditions ?? RuleConditions.Any,
        Action = action,
        Lifetime = RuleLifetime.Permanent,
        Source = RuleSource.User,
        CreatedAt = T0,
        UpdatedAt = T0,
    };

    private static CompileResult Compile(FilterMode mode, params Rule[] rules) => Compile(mode, _ => [], rules);

    private static CompileResult Compile(FilterMode mode, Func<DomainPattern, IReadOnlyList<IPAddress>> resolve, params Rule[] rules) =>
        RuleCompiler.Compile(new CompileInput
        {
            Rules = rules,
            Mode = mode,
            SelfPath = @"C:\Program Files\NetworkControl\NetworkControl.Service.exe",
            SystemServiceHostPath = @"C:\Windows\System32\svchost.exe",
            ResolveDomain = resolve,
            ExpandPath = p => [p.Replace("*", "1.0", StringComparison.Ordinal)],
        });

    [Fact]
    public void Ask_mode_adds_default_block_with_lowest_weight()
    {
        var result = Compile(FilterMode.Ask, NewRule(VerdictAction.Allow, @"C:\a.exe"));
        var defaults = result.Filters.Where(f => f.Role == FilterRole.DefaultBlock).ToList();

        Assert.Equal(2, defaults.Count);
        Assert.All(defaults, f => Assert.False(f.Permit));
        Assert.Equal(result.Filters.Min(f => f.Weight), defaults[0].Weight);
        Assert.Contains(result.Filters, f => f is { Role: FilterRole.Builtin, LoopbackOnly: true });
        Assert.Contains(result.Filters, f => f.Role == FilterRole.Builtin && f.AppPath?.EndsWith("NetworkControl.Service.exe", StringComparison.Ordinal) == true);
    }

    [Theory]
    [InlineData(FilterMode.Blacklist)]
    [InlineData(FilterMode.Disabled)]
    public void Permissive_modes_have_no_default_block(FilterMode mode) =>
        Assert.DoesNotContain(Compile(mode, NewRule(VerdictAction.Deny, @"C:\a.exe")).Filters, f => f.Role == FilterRole.DefaultBlock);

    [Fact]
    public void Disabled_mode_ignores_rules() =>
        Assert.DoesNotContain(Compile(FilterMode.Disabled, NewRule(VerdictAction.Deny, @"C:\a.exe")).Filters, f => f.Role == FilterRole.Rule);

    [Fact]
    public void Block_all_keeps_only_builtins_and_block_all()
    {
        var result = Compile(FilterMode.BlockAll, NewRule(VerdictAction.Allow, @"C:\a.exe"));
        Assert.All(result.Filters, f => Assert.True(f.Role is FilterRole.Builtin or FilterRole.BlockAll));
        Assert.True(result.Filters.Where(f => f.Role == FilterRole.Builtin).Min(f => f.Weight)
            > result.Filters.Where(f => f.Role == FilterRole.BlockAll).Max(f => f.Weight));
    }

    [Fact]
    public void Rule_weights_follow_rule_set_order()
    {
        var first = NewRule(VerdictAction.Allow, @"C:\a.exe", new RuleConditions { Remote = new DomainTarget("x.com") });
        var second = NewRule(VerdictAction.Deny, @"C:\a.exe");
        var result = Compile(FilterMode.Ask, _ => [IPAddress.Parse("203.0.113.5")], first, second);

        var w1 = result.Filters.Where(f => f.RuleId == first.Id).Max(f => f.Weight);
        var w2 = result.Filters.Where(f => f.RuleId == second.Id).Max(f => f.Weight);
        Assert.True(w1 > w2);
        Assert.True(w2 > RuleCompiler.DefaultBlockWeight);
        Assert.True(w1 < RuleCompiler.BlockAllWeight);
    }

    [Fact]
    public void Domain_rule_waits_for_dns_then_expands_to_addresses()
    {
        var rule = NewRule(VerdictAction.Allow, @"C:\a.exe", new RuleConditions { Remote = new DomainTarget("*.github.com") });

        var before = Compile(FilterMode.Ask, rule);
        Assert.DoesNotContain(before.Filters, f => f.RuleId == rule.Id);
        Assert.Contains(before.Skipped, s => s.RuleId == rule.Id);

        var after = Compile(FilterMode.Ask, _ => [IPAddress.Parse("140.82.112.3"), IPAddress.Parse("2606:50c0::1")], rule);
        var filters = after.Filters.Where(f => f.RuleId == rule.Id).ToList();
        Assert.Equal(4, filters.Count);
        Assert.Contains(filters, f => !f.Inbound && f.Family == IpFamily.V4 && f.RemoteNetworks.Single().ToString() == "140.82.112.3/32");
        Assert.Contains(filters, f => !f.Inbound && f.Family == IpFamily.V6);
        Assert.Equal(2, filters.Count(f => f.Inbound));
    }

    [Fact]
    public void Internet_zone_excludes_private_networks()
    {
        var rule = NewRule(VerdictAction.Deny, @"C:\a.exe", new RuleConditions { Remote = new ZoneTarget(NetworkZone.Internet) });
        var v4 = Compile(FilterMode.Ask, rule).Filters.Single(f => f.RuleId == rule.Id && f.Family == IpFamily.V4 && !f.Inbound);

        bool Covered(string ip) => v4.RemoteNetworks.Any(n => n.Contains(IPAddress.Parse(ip)));
        Assert.True(Covered("8.8.8.8"));
        Assert.True(Covered("140.82.112.3"));
        Assert.False(Covered("10.1.2.3"));
        Assert.False(Covered("192.168.1.1"));
        Assert.False(Covered("127.0.0.1"));
    }

    [Fact]
    public void Inbound_rules_compile_to_recv_accept_and_service_rules_are_skipped()
    {
        var inbound = NewRule(VerdictAction.Allow, @"C:\a.exe", new RuleConditions { Direction = Direction.Inbound });
        var service = NewRule(VerdictAction.Allow) with { App = new AppMatcher { PathPattern = @"C:\Windows\System32\svchost.exe", ServiceName = "wuauserv" } };
        var result = Compile(FilterMode.Ask, inbound, service);

        var filters = result.Filters.Where(f => f.RuleId == inbound.Id).ToList();
        Assert.Equal(2, filters.Count);
        Assert.All(filters, f => Assert.True(f.Inbound));
        Assert.DoesNotContain(result.Filters, f => f.RuleId == service.Id);
        Assert.Contains(result.Skipped, s => s.RuleId == service.Id);
    }

    [Fact]
    public void Dual_direction_rules_compile_to_both_layers()
    {
        var rule = NewRule(VerdictAction.Deny, @"C:\a.exe");
        var filters = Compile(FilterMode.Ask, rule).Filters.Where(f => f.RuleId == rule.Id).ToList();

        Assert.Equal(4, filters.Count);
        Assert.Equal(2, filters.Count(f => f.Inbound));
        Assert.Equal(2, filters.Count(f => !f.Inbound));
    }

    [Fact]
    public void Inbound_default_deny_adds_only_inbound_default_filters()
    {
        var result = RuleCompiler.Compile(new CompileInput
        {
            Rules = [],
            Mode = FilterMode.Ask,
            InboundDefaultDeny = true,
            ResolveDomain = _ => [],
            ExpandPath = p => [p],
        });

        var inbound = result.Filters.Where(f => f.Role == FilterRole.InboundDefaultBlock).ToList();
        Assert.Equal(2, inbound.Count);
        Assert.All(inbound, f => Assert.True(f.Inbound));
        Assert.Equal(2, result.Filters.Count(f => f.Role == FilterRole.DefaultBlock && !f.Inbound));
    }

    [Fact]
    public void Wildcard_paths_are_expanded_and_ports_preserved()
    {
        var rule = NewRule(VerdictAction.Allow, @"C:\Apps\app-*\app.exe", new RuleConditions { RemotePort = new PortRange(8000, 8080), Protocol = L4Protocol.Tcp });
        var filter = Compile(FilterMode.Ask, rule).Filters.First(f => f.RuleId == rule.Id);

        Assert.Equal(@"C:\Apps\app-1.0\app.exe", filter.AppPath);
        Assert.Equal(new PortRange(8000, 8080), filter.RemotePort);
        Assert.Equal(L4Protocol.Tcp, filter.Protocol);
    }

    [Fact]
    public void Filter_key_changes_with_behaviour()
    {
        var a = new FilterSpec { Name = "x", Family = IpFamily.V4, Permit = true, Weight = 5, Role = FilterRole.Rule, AppPath = @"C:\a.exe" };
        Assert.Equal(a.Key, (a with { Name = "renamed" }).Key);
        Assert.NotEqual(a.Key, (a with { Permit = false }).Key);
        Assert.NotEqual(a.Key, (a with { Weight = 6 }).Key);
        Assert.Equal(a.Key, (a with { AppPath = @"c:\A.EXE" }).Key);
        Assert.NotEqual(a.Key, (a with { Inbound = true }).Key);
    }
}

public class IpRangeMathTests
{
    [Fact]
    public void Complement_covers_everything_except_excluded()
    {
        var excluded = new[] { IPNetwork.Parse("10.0.0.0/8"), IPNetwork.Parse("192.168.0.0/16") };
        var complement = IpRangeMath.Complement(excluded, AddressFamily.InterNetwork);

        var random = new Random(7);
        for (var i = 0; i < 2000; i++)
        {
            var address = new IPAddress(random.Next(int.MinValue, int.MaxValue) & 0xFFFFFFFFL);
            var inExcluded = excluded.Any(n => n.Contains(address));
            var inComplement = complement.Any(n => n.Contains(address));
            Assert.NotEqual(inExcluded, inComplement);
        }
    }

    [Fact]
    public void Complement_of_nothing_is_everything() =>
        Assert.Equal("0.0.0.0/0", IpRangeMath.Complement([], AddressFamily.InterNetwork).Single().ToString());
}

public class DomainIndexTests
{
    [Fact]
    public void Resolves_wildcards_and_expires()
    {
        var time = new FakeTimeProvider(DateTimeOffset.UtcNow);
        var index = new DomainIndex(time);

        Assert.True(index.Observe("api.github.com", [IPAddress.Parse("140.82.112.3")]));
        Assert.False(index.Observe("api.github.com", [IPAddress.Parse("140.82.112.3")]));
        Assert.Single(index.Resolve(DomainPattern.Parse("*.github.com")));
        Assert.Empty(index.Resolve(DomainPattern.Parse("example.com")));

        time.Advance(DomainIndex.Retention + TimeSpan.FromSeconds(1));
        Assert.Empty(index.Resolve(DomainPattern.Parse("*.github.com")));
        Assert.True(index.Prune());
    }
}

public class DnsResultParserTests
{
    [Fact]
    public void Parses_addresses_and_skips_cname_records()
    {
        var addresses = DnsResultParser.Parse("type:  5 www.bing.com.trafficmanager.net;::ffff:204.79.197.200;2620:1ec:c11::200;");
        Assert.Equal(["204.79.197.200", "2620:1ec:c11::200"], addresses.Select(a => a.ToString()));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("type:  5 only.cname.example;")]
    public void Empty_or_cname_only_results_yield_nothing(string? results) => Assert.Empty(DnsResultParser.Parse(results));
}

public class DevicePathResolverTests
{
    [Fact]
    public void Converts_device_paths_to_drive_paths()
    {
        var resolver = new DevicePathResolver(() => new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            [@"\Device\HarddiskVolume3"] = "C:",
            [@"\Device\HarddiskVolume12"] = "D:",
        });

        Assert.Equal(@"C:\program files\app\app.exe", resolver.ToDosPath(@"\device\harddiskvolume3\program files\app\app.exe"));
        Assert.Equal(@"D:\games\x.exe", resolver.ToDosPath(@"\device\harddiskvolume12\games\x.exe"));
        // 前綴必須在路徑分隔處結束：volume3 不能匹配 volume30
        Assert.Equal(@"\device\harddiskvolume30\a.exe", resolver.ToDosPath(@"\device\harddiskvolume30\a.exe"));
    }
}
