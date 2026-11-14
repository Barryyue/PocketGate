using System.Net;
using NetworkControl.Core.Domain;
using NetworkControl.Engine.Windows.Compilation;

namespace NetworkControl.Engine.Windows.IntegrationTests;

/// <summary>
/// RuleCompiler 的輸出經 WfpSession 增量下發：只增刪變化的過濾器，未變化的保留同一個 filterId。
/// 所有過濾器只針對 127.0.0.2 的測試端口，不影響本機真實網絡。
/// </summary>
public class RulePushTests
{
    private static readonly DateTimeOffset T0 = new(2026, 11, 14, 8, 0, 0, TimeSpan.Zero);

    [Fact]
    public void IncrementalPush_OnlyAddsAndRemovesChangedFilters()
    {
        TestEnvironment.RequireAdministrator();

        using var session = TestSupport.OpenSession();
        var installed = new Dictionary<string, (FilterSpec Spec, ulong Id)>(StringComparer.Ordinal);

        (IReadOnlyList<ulong> Added, IReadOnlyList<ulong> Removed) Apply(IReadOnlyList<FilterSpec> desiredSpecs)
        {
            var desired = desiredSpecs
                .GroupBy(f => f.Key, StringComparer.Ordinal)
                .ToDictionary(g => g.Key, g => g.First(), StringComparer.Ordinal);
            var remove = installed
                .Where(kv => !desired.ContainsKey(kv.Key))
                .Select(kv => kv.Value.Id)
                .ToArray();
            var add = desired
                .Where(kv => !installed.ContainsKey(kv.Key))
                .Select(kv => kv.Value)
                .ToArray();

            var added = session.Apply(remove, add, TestSupport.FailOnSkipped);

            foreach (var key in installed.Where(kv => remove.Contains(kv.Value.Id)).Select(kv => kv.Key).ToArray())
            {
                installed.Remove(key);
            }

            foreach (var (spec, id) in added)
            {
                installed[spec.Key] = (spec, id);
            }

            return (added.Values.ToArray(), remove);
        }

        var rule1 = NewRule(IPAddress.Parse("127.0.0.2"), 55001);
        var rule2 = NewRule(IPAddress.Parse("127.0.0.2"), 55002);

        var first = Apply(Compile(rule1));
        var filter1 = Assert.Single(installed.Values).Id;
        Assert.Empty(first.Removed);

        var second = Apply(Compile(rule1, rule2));
        Assert.Single(second.Added);
        Assert.Empty(second.Removed);
        Assert.Equal(2, installed.Count);
        Assert.Contains(installed.Values, v => v.Id == filter1);
        Assert.True(WfpTestNative.FilterExists(filter1));

        var secondId = installed.Values.Single(v => v.Id != filter1).Id;
        Assert.True(WfpTestNative.FilterExists(secondId));

        var third = Apply(Compile(rule1));
        Assert.Empty(third.Added);
        Assert.Equal(secondId, Assert.Single(third.Removed));
        Assert.True(WfpTestNative.FilterExists(filter1));
        Assert.False(WfpTestNative.FilterExists(secondId));
    }

    private static IReadOnlyList<FilterSpec> Compile(params Rule[] rules) =>
        RuleCompiler.Compile(new CompileInput
        {
            Rules = rules,
            Mode = FilterMode.Blacklist,
            ResolveDomain = _ => [],
            ExpandPath = _ => [],
        })
        .Filters
        .Where(f => f.Role == FilterRole.Rule && f.Family == IpFamily.V4)
        .ToArray();

    private static Rule NewRule(IPAddress remote, int port) => new()
    {
        Id = Guid.NewGuid(),
        App = null,
        Conditions = new RuleConditions
        {
            Direction = Direction.Outbound,
            Protocol = L4Protocol.Tcp,
            Remote = AddressTarget.Single(remote),
            RemotePort = PortRange.Single((ushort)port),
        },
        Action = VerdictAction.Deny,
        Lifetime = RuleLifetime.Permanent,
        Source = RuleSource.User,
        CreatedAt = T0,
        UpdatedAt = T0,
    };
}
