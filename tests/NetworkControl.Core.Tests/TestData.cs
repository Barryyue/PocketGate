using System.Net;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;
using NetworkControl.Core.Rules;

namespace NetworkControl.Core.Tests;

/// <summary>測試數據構造器。</summary>
internal static class TestData
{
    public static readonly DateTimeOffset T0 = new(2026, 11, 12, 8, 0, 0, TimeSpan.Zero);

    public static AppIdentity App(long id, string path, string? signer = null, string? sha256 = null) => new()
    {
        AppId = id,
        Path = path,
        Signature = signer is null ? null : new CodeSignature(signer),
        Sha256 = sha256,
    };

    public static FlowInfo Flow(string remote = "140.82.112.3", int port = 443, L4Protocol protocol = L4Protocol.Tcp,
        Direction direction = Direction.Outbound, ProcessRef? process = null, FlowOrigin origin = FlowOrigin.Pending) => new()
        {
            FlowId = 1,
            Direction = direction,
            Protocol = protocol,
            Local = new IPEndPoint(IPAddress.Parse("192.168.1.10"), 50_000),
            Remote = new IPEndPoint(IPAddress.Parse(remote), port),
            Process = process ?? new ProcessRef(1234, 1),
            Timestamp = T0,
            Origin = origin,
        };

    public static Rule Rule(
        VerdictAction action,
        AppMatcher? app = null,
        RuleConditions? conditions = null,
        int manualPriority = 0,
        DateTimeOffset? updatedAt = null) => new()
        {
            Id = Guid.NewGuid(),
            App = app,
            Conditions = conditions ?? RuleConditions.Any,
            Action = action,
            Lifetime = RuleLifetime.Permanent,
            Source = RuleSource.User,
            ManualPriority = manualPriority,
            CreatedAt = T0,
            UpdatedAt = updatedAt ?? T0,
        };

    public static AppMatcher Matcher(string path, string? signer = null, string? sha = null) =>
        new() { PathPattern = path, RequiredSignerId = signer, RequiredSha256 = sha };

    public static EvaluationContext Context(FlowInfo flow, AppIdentity? app, params string[] domains) =>
        new(flow, app, domains.Length == 0 ? DomainCandidates.None : new DomainCandidates(domains), NetworkZones.Classify(flow.Remote.Address));

    public static RuleSet RuleSetOf(params Rule[] rules)
    {
        var engine = new RuleEngine();
        return engine.Publish(rules);
    }
}
