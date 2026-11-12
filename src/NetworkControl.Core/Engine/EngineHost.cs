using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Pipeline;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Engine;

public enum EngineState : byte
{
    NotStarted = 0,
    Starting = 1,
    Running = 2,
    Faulted = 3,
    Stopped = 4,
}

/// <summary>引擎運行狀態，供 UI 顯示。</summary>
public sealed class EngineStatus
{
    private volatile EngineFault? _lastFault;
    private volatile int _state;

    public event EventHandler? Changed;

    public string Name { get; internal set; } = "";

    public EngineCapabilities Capabilities { get; internal set; }

    public EngineState State => (EngineState)_state;

    public EngineFault? LastFault => _lastFault;

    internal void Set(EngineState state, EngineFault? fault = null)
    {
        _state = (int)state;
        if (fault is not null)
        {
            _lastFault = fault;
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }
}

/// <summary>核心對 <see cref="IEngineHost"/> 的實現：把引擎回調接入判決管線、DNS 映射等核心組件。</summary>
public sealed partial class EngineHost(
    VerdictPipeline pipeline,
    DnsMapper dns,
    RuleEngine rules,
    RuntimeStateService state,
    SettingsService settings,
    EngineStatus status,
    IHostApplicationLifetime lifetime,
    ILogger<EngineHost> logger) : IEngineHost
{
    public RuleEngine Rules => rules;

    public FilterMode CurrentMode => state.Mode;

    public FailPolicy FailPolicy => settings.Current.FailPolicy;

    public async ValueTask<VerdictAction> DecideAsync(FlowInfo flow)
    {
        var verdict = await pipeline.DecideAsync(flow, lifetime.ApplicationStopping);
        return verdict.Action;
    }

    /// <summary>
    /// 被默認阻斷攔下的連接：走判決管線觸發詢問。被用戶規則攔下的連接：同樣走管線，
    /// 規則階段會命中那條拒絕規則，只記錄日誌，不會彈窗。
    /// </summary>
    public void OnFlowBlocked(FlowInfo flow, BlockReason reason) =>
        _ = DecideBlockedAsync(flow with { Origin = FlowOrigin.BlockedEvent });

    public void OnDnsObserved(DnsObservation observation) => dns.Observe(observation);

    public void OnTraffic(ReadOnlySpan<TrafficSample> samples)
    {
        // P1：流量統計（FR-STA-01）在此接入 TrafficAggregator
    }

    public void OnEngineFault(EngineFault fault)
    {
        LogEngineFault(logger, fault.Exception, fault.Code, fault.Message, fault.IsFatal);
        if (fault.IsFatal)
        {
            status.Set(EngineState.Faulted, fault);
        }
    }

    private async Task DecideBlockedAsync(FlowInfo flow)
    {
        try
        {
            await pipeline.DecideAsync(flow, lifetime.ApplicationStopping);
        }
        catch (Exception ex)
        {
            LogBlockedEventFailed(logger, ex, flow.FlowId);
        }
    }

    [LoggerMessage(Level = LogLevel.Error, Message = "引擎故障 [{Code}] {Message}（致命：{IsFatal}）")]
    private static partial void LogEngineFault(ILogger logger, Exception? exception, string code, string message, bool isFatal);

    [LoggerMessage(Level = LogLevel.Error, Message = "處理阻斷事件 {FlowId} 失敗")]
    private static partial void LogBlockedEventFailed(ILogger logger, Exception exception, ulong flowId);
}

/// <summary>啟動和停止已註冊的網絡引擎。</summary>
public sealed partial class EngineHostedService(
    INetworkEngine engine,
    EngineHost host,
    EngineStatus status,
    PromptBroker prompts,
    ILogger<EngineHostedService> logger) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        status.Name = engine.Name;
        status.Capabilities = engine.Capabilities;
        prompts.EngineHoldsConnections = engine.Capabilities.HasFlag(EngineCapabilities.PendingVerdict);
        status.Set(EngineState.Starting);

        try
        {
            await engine.StartAsync(host, cancellationToken);
            status.Set(EngineState.Running);
            LogStarted(logger, engine.Name, engine.Capabilities);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            // 引擎失敗不讓服務退出：保持 IPC 可用，讓 UI 顯示原因（設計文檔 §16）
            status.Set(EngineState.Faulted, new EngineFault("engine.start", ex.Message, IsFatal: true, ex));
            LogStartFailed(logger, ex, engine.Name);
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        prompts.CancelAll();
        if (status.State is EngineState.Running or EngineState.Starting)
        {
            await engine.StopAsync(cancellationToken);
        }

        status.Set(EngineState.Stopped);
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "網絡引擎 {Name} 已啟動，能力：{Capabilities}")]
    private static partial void LogStarted(ILogger logger, string name, EngineCapabilities capabilities);

    [LoggerMessage(Level = LogLevel.Critical, Message = "網絡引擎 {Name} 啟動失敗")]
    private static partial void LogStartFailed(ILogger logger, Exception exception, string name);
}
