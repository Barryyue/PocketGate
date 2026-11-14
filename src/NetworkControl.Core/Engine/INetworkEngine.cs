using System.Net;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Rules;

namespace NetworkControl.Core.Engine;

/// <summary>引擎能力聲明。核心和 UI 據此調整行為，而不是判斷操作系統。</summary>
[Flags]
public enum EngineCapabilities
{
    None = 0,

    /// <summary>能掛起連接等待判決（Linux NFQUEUE、macOS NE、Windows 驅動）。</summary>
    PendingVerdict = 1 << 0,

    /// <summary>判決以「下發底層規則」的方式生效（Windows 用戶態 WFP）。</summary>
    RulePush = 1 << 1,

    ProvidesPid = 1 << 2,
    ProvidesHostname = 1 << 3,
    Inbound = 1 << 4,
    TrafficStats = 1 << 5,
    KillConnection = 1 << 6,
    BandwidthLimit = 1 << 7,
    BootProtection = 1 << 8,
}

/// <summary>連接被阻斷的原因（RulePush 類引擎上報）。</summary>
public enum BlockReason : byte
{
    /// <summary>被默認阻斷規則攔下（無規則命中）：需要詢問用戶。</summary>
    DefaultBlock = 0,

    /// <summary>被用戶規則攔下：只記日誌。</summary>
    UserRule = 1,

    /// <summary>被默認入站阻斷攔下：只記錄，不詢問。</summary>
    InboundDefault = 2,
}

/// <summary>按應用聚合的流量樣本。</summary>
public readonly record struct TrafficSample(ProcessRef? Process, string? ExecutablePath, long BytesIn, long BytesOut, DateTimeOffset Timestamp);

/// <summary>連接標識，用於斷開連接等操作。</summary>
public readonly record struct ConnectionKey(L4Protocol Protocol, IPEndPoint Local, IPEndPoint Remote);

/// <summary>引擎故障信息。</summary>
public sealed record EngineFault(string Code, string Message, bool IsFatal, Exception? Exception = null);

/// <summary>
/// 【平台實現】底層攔截引擎。每個平台一個實現，由服務在啟動時按配置裝配。
/// </summary>
public interface INetworkEngine : IAsyncDisposable
{
    string Name { get; }

    EngineCapabilities Capabilities { get; }

    Task StartAsync(IEngineHost host, CancellationToken cancellationToken);

    Task StopAsync(CancellationToken cancellationToken);

    /// <summary>斷開連接（需要 <see cref="EngineCapabilities.KillConnection"/>）。</summary>
    ValueTask<bool> KillConnectionAsync(ConnectionKey key, CancellationToken cancellationToken) => ValueTask.FromResult(false);
}

/// <summary>
/// 由核心實現、供引擎回調的接口。引擎只負責「攔截」和「採集」，所有決策都在核心中完成。
/// </summary>
public interface IEngineHost
{
    /// <summary>PendingVerdict 類引擎：每條新連接調用一次。返回值可能在很久以後（等待用戶）才完成。</summary>
    ValueTask<VerdictAction> DecideAsync(FlowInfo flow);

    /// <summary>RulePush 類引擎：連接已被默認阻斷規則攔下後上報，用於觸發詢問。</summary>
    void OnFlowBlocked(FlowInfo flow, BlockReason reason);

    void OnDnsObserved(DnsObservation observation);

    void OnTraffic(ReadOnlySpan<TrafficSample> samples);

    void OnEngineFault(EngineFault fault);

    /// <summary>當前生效的規則集（RulePush 類引擎訂閱 <see cref="RuleEngine.RuleSetChanged"/> 自行編譯）。</summary>
    RuleEngine Rules { get; }

    FilterMode CurrentMode { get; }

    FailPolicy FailPolicy { get; }
}
