using NetworkControl.Core.Apps;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;
using NetworkControl.Core.Rules;

namespace NetworkControl.Core.Pipeline;

/// <summary>
/// 一條連接在判決管線中的上下文。應用身份、候選域名等信息按需惰性解析並緩存，
/// 前面的階段（如內置放行）可以在不做任何解析的情況下直接給出判決。
/// </summary>
public sealed class FlowContext
{
    private readonly IAppIdentityResolver _resolver;
    private readonly DnsMapper _dns;
    private bool _appResolved;
    private AppIdentity? _app;
    private DomainCandidates? _domains;
    private NetworkZone? _zone;

    internal FlowContext(FlowInfo flow, IAppIdentityResolver resolver, DnsMapper dns, DateTimeOffset now, CancellationToken cancellationToken)
    {
        Flow = flow;
        _resolver = resolver;
        _dns = dns;
        Now = now;
        CancellationToken = cancellationToken;
    }

    public FlowInfo Flow { get; }

    public DateTimeOffset Now { get; }

    public CancellationToken CancellationToken { get; }

    public NetworkZone RemoteZone => _zone ??= NetworkZones.Classify(Flow.Remote.Address);

    public DomainCandidates Domains => _domains ??= _dns.Lookup(Flow.Remote.Address, Flow.Process, Flow.EngineHostname);

    /// <summary>規則階段的求值結果，供後續階段使用（例如檢測篡改）。</summary>
    public RuleMatch? Match { get; set; }

    /// <summary>已解析的應用身份（未解析時為 null，需先調用 <see cref="GetAppAsync"/>）。</summary>
    public AppIdentity? App => _app;

    public async ValueTask<AppIdentity?> GetAppAsync()
    {
        if (!_appResolved)
        {
            _app = await _resolver.ResolveAsync(Flow, CancellationToken);
            _appResolved = true;
        }

        return _app;
    }

    public EvaluationContext ToEvaluationContext() => new(Flow, _app, Domains, RemoteZone);
}

/// <summary>
/// 【擴展點】判決階段。按 <see cref="Order"/> 從小到大執行，第一個返回非 null 判決的階段決定結果。
/// 新功能（黑名單訂閱、流量配額、時間段規則等）通過註冊新階段接入，無需修改管線本身。
/// </summary>
public interface IVerdictStage
{
    int Order { get; }

    ValueTask<Verdict?> EvaluateAsync(FlowContext context);
}

/// <summary>內置階段的順序常量，為擴展階段預留間隔。</summary>
public static class StageOrder
{
    public const int DisabledMode = 100;
    public const int Builtin = 200;
    public const int BlockAll = 300;
    public const int Rules = 500;
    public const int Fallback = 1000;
}
