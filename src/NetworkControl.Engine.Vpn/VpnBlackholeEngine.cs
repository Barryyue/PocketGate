using System.Threading.Channels;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Engine.Vpn.Platform;
using NetworkControl.Engine.Vpn.Routing;

namespace NetworkControl.Engine.Vpn;

/// <summary>VPN 引擎配置。</summary>
public sealed class VpnEngineOptions
{
    /// <summary>本應用的包名：始終排除在 VPN 之外。</summary>
    public string SelfPackage { get; set; } = "";

    /// <summary>規則、模式、應用列表變化後等待多久再重新規劃（合併頻繁的變化）。</summary>
    public TimeSpan ReplanDebounce { get; set; } = TimeSpan.FromMilliseconds(300);

    /// <summary>同一應用到同一目標的重複嘗試在這段時間內只上報一次。</summary>
    public TimeSpan ReportDedupWindow { get; set; } = TimeSpan.FromSeconds(5);

    /// <summary>
    /// 被明確拒絕的連線立即回 TCP RST / ICMP 端口不可達（docs/m3-tasks.md M3-05）。
    /// 等待詢問的連線不受影響，保持黑洞。出問題時可以關掉。
    /// </summary>
    public bool FastFail { get; set; } = true;
}

/// <summary>
/// 黑洞式分流 VPN 引擎（docs/android.md §5.6）。允許的應用被排除在 VPN 之外，其餘應用的報文進入虛擬網卡後被丟棄，
/// 聯網嘗試以 <see cref="Core.Domain.FlowOrigin.BlockedEvent"/> 上報給核心觸發詢問；規則、模式、應用列表或保護開關變化時重新規劃並按需重建虛擬網卡。
/// </summary>
public sealed partial class VpnBlackholeEngine : INetworkEngine
{
    public const string EngineName = "AndroidVpn";

    private readonly IVpnTunnel _tunnel;
    private readonly IInstalledAppCatalog _catalog;
    private readonly IConnectionOwnerResolver _owners;
    private readonly IProtectionSwitch _protection;
    private readonly RuntimeStateService _state;
    private readonly VpnEngineOptions _options;
    private readonly TimeProvider _time;
    private readonly ILogger<VpnBlackholeEngine> _logger;
    private readonly Channel<bool> _replan = Channel.CreateBounded<bool>(new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropWrite });
    private readonly Lock _lifecycle = new();

    private IEngineHost? _host;
    private BlockedFlowReporter? _reporter;
    private CancellationTokenSource? _cts;
    private Task _loop = Task.CompletedTask;
    private Task _reporting = Task.CompletedTask;
    private VpnRoutingPlan _plan = VpnRoutingPlan.Empty;
    private VpnTunnelConfig? _applied;
    private VpnTunnelState _lastTunnelState;

    public VpnBlackholeEngine(
        IVpnTunnel tunnel,
        IInstalledAppCatalog catalog,
        IConnectionOwnerResolver owners,
        IProtectionSwitch protection,
        RuntimeStateService state,
        IOptions<VpnEngineOptions> options,
        TimeProvider time,
        ILogger<VpnBlackholeEngine> logger)
    {
        _tunnel = tunnel;
        _catalog = catalog;
        _owners = owners;
        _protection = protection;
        _state = state;
        _options = options.Value;
        _time = time;
        _logger = logger;
    }

    /// <summary>重新規劃完成（計劃可能未變）或虛擬網卡狀態變化後觸發。</summary>
    public event EventHandler? StatusChanged;

    public string Name => EngineName;

    public EngineCapabilities Capabilities => EngineCapabilities.RulePush;

    public VpnEngineCounters Counters { get; } = new();

    /// <summary>最近一次的路由規劃結果。</summary>
    public VpnRoutingPlan CurrentPlan => Volatile.Read(ref _plan);

    public VpnTunnelState TunnelState => _tunnel.State;

    public string? TunnelError => _tunnel.LastError;

    public bool IsProtectionEnabled => _protection.IsEnabled;

    public Task StartAsync(IEngineHost host, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(host);
        lock (_lifecycle)
        {
            if (_cts is not null)
            {
                return Task.CompletedTask;
            }

            _host = host;
            _cts = new CancellationTokenSource();
            _reporter = new BlockedFlowReporter(_owners, _catalog, () => CurrentPlan, Counters, _time, _options.ReportDedupWindow, _logger, fastFail: _options.FastFail);

            host.Rules.RuleSetChanged += OnRuleSetChanged;
            _state.Changed += OnInputChanged;
            _catalog.Changed += OnInputChanged;
            _protection.Changed += OnInputChanged;
            _tunnel.StateChanged += OnTunnelStateChanged;

            // 後台循環不能繼承調用方的同步上下文：宿主可能在界面線程上啟動（Android 上在主線程做 UID 查詢會拋出 NetworkOnMainThreadException）
            var reporter = _reporter;
            var token = _cts.Token;
            _reporting = Task.Run(() => reporter.RunAsync(host, token), CancellationToken.None);
            _loop = Task.Run(() => RunAsync(token), CancellationToken.None);
        }

        // 立即規劃一次：引擎啟動後保護開關已打開時馬上建立虛擬網卡
        RequestReplan();
        return Task.CompletedTask;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        CancellationTokenSource? cts;
        lock (_lifecycle)
        {
            cts = _cts;
            _cts = null;
            if (cts is null)
            {
                return;
            }

            _host!.Rules.RuleSetChanged -= OnRuleSetChanged;
            _state.Changed -= OnInputChanged;
            _catalog.Changed -= OnInputChanged;
            _protection.Changed -= OnInputChanged;
            _tunnel.StateChanged -= OnTunnelStateChanged;
        }

        await cts.CancelAsync();
        _reporter?.Complete();
        await Task.WhenAll(_loop, _reporting);
        cts.Dispose();

        try
        {
            await _tunnel.CloseAsync(cancellationToken);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            LogCloseFailed(_logger, ex);
        }

        _applied = null;
    }

    public async ValueTask DisposeAsync() => await StopAsync(CancellationToken.None);

    /// <summary>請求重新規劃（例如用戶剛授權 VPN 之後）。</summary>
    public void RequestReplan() => _replan.Writer.TryWrite(true);

    private void OnRuleSetChanged(object? sender, RuleSetChangedEventArgs e) => RequestReplan();

    private void OnInputChanged(object? sender, EventArgs e) => RequestReplan();

    private void OnTunnelStateChanged(object? sender, EventArgs e)
    {
        var state = _tunnel.State;
        var previous = _lastTunnelState;
        _lastTunnelState = state;
        if (state is VpnTunnelState.Revoked or VpnTunnelState.Failed or VpnTunnelState.Closed)
        {
            _applied = null;
        }

        if (state == VpnTunnelState.Revoked && previous != VpnTunnelState.Revoked)
        {
            // 用戶啟用了其他 VPN 或在系統設定中斷開：不自動搶回，關閉保護開關，由用戶決定
            LogRevoked(_logger);
            _host?.OnEngineFault(new EngineFault("vpn.revoked", _tunnel.LastError ?? "VPN 已被系統撤銷。", IsFatal: false));
            _protection.SetEnabled(false);
        }

        StatusChanged?.Invoke(this, EventArgs.Empty);
    }

    private async Task RunAsync(CancellationToken cancellationToken)
    {
        try
        {
            while (await _replan.Reader.WaitToReadAsync(cancellationToken))
            {
                if (_options.ReplanDebounce > TimeSpan.Zero)
                {
                    await Task.Delay(_options.ReplanDebounce, _time, cancellationToken);
                }

                while (_replan.Reader.TryRead(out _))
                {
                    // 合併防抖期間的多次請求
                }

                try
                {
                    await ReplanAsync(cancellationToken);
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    LogReplanFailed(_logger, ex);
                    _host?.OnEngineFault(new EngineFault("vpn.apply", ex.Message, IsFatal: false, ex));
                }

                StatusChanged?.Invoke(this, EventArgs.Empty);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // 停止
        }
    }

    internal async Task ReplanAsync(CancellationToken cancellationToken)
    {
        var host = _host ?? throw new InvalidOperationException("引擎尚未啟動。");
        var plan = VpnRoutingPlanner.Plan(new VpnRoutingInput
        {
            Rules = host.Rules.Current,
            Mode = host.CurrentMode,
            Apps = _catalog.GetApps(),
            SelfPackage = _options.SelfPackage,
        });
        Volatile.Write(ref _plan, plan);

        if (!_protection.IsEnabled)
        {
            if (_tunnel.State is not (VpnTunnelState.Closed or VpnTunnelState.NotPrepared or VpnTunnelState.Revoked))
            {
                LogClosing(_logger);
                await _tunnel.CloseAsync(cancellationToken);
            }

            _applied = null;
            return;
        }

        if (!_tunnel.IsPrepared)
        {
            // 用戶尚未授權：由界面引導授權後再調用 RequestReplan
            LogNotPrepared(_logger);
            return;
        }

        var config = VpnTunnelConfig.From(plan);
        if (_tunnel.State == VpnTunnelState.Active && config.IsEquivalentTo(_applied))
        {
            return;
        }

        LogApplying(_logger, plan.Mode, config.BypassPackages.Count, plan.ByUid.Count, plan.SkippedRules.Count);
        await _tunnel.ApplyAsync(config, _reporter!, cancellationToken);
        _applied = config;
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "建立 VPN 分流：模式 {Mode}，放行 {Bypass} 個包（共 {Apps} 個 UID），{Skipped} 條規則在此平台不生效")]
    private static partial void LogApplying(ILogger logger, Core.Domain.FilterMode mode, int bypass, int apps, int skipped);

    [LoggerMessage(Level = LogLevel.Information, Message = "保護已關閉，關閉虛擬網卡")]
    private static partial void LogClosing(ILogger logger);

    [LoggerMessage(Level = LogLevel.Information, Message = "保護已開啟，但用戶尚未授權建立 VPN")]
    private static partial void LogNotPrepared(ILogger logger);

    [LoggerMessage(Level = LogLevel.Warning, Message = "VPN 已被系統撤銷，保護已關閉")]
    private static partial void LogRevoked(ILogger logger);

    [LoggerMessage(Level = LogLevel.Error, Message = "重新規劃 VPN 分流失敗")]
    private static partial void LogReplanFailed(ILogger logger, Exception exception);

    [LoggerMessage(Level = LogLevel.Warning, Message = "關閉虛擬網卡失敗")]
    private static partial void LogCloseFailed(ILogger logger, Exception exception);
}
