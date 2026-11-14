using System.Collections.Concurrent;
using System.Collections.Frozen;
using System.Net;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Threading.Channels;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Matching;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Engine.Windows.Compilation;
using NetworkControl.Engine.Windows.Dns;
using NetworkControl.Engine.Windows.Interop;
using NetworkControl.Engine.Windows.Platform;

namespace NetworkControl.Engine.Windows;

/// <summary>
/// Windows 用戶態 WFP 引擎（設計文檔 §10）。
/// <list type="bullet">
/// <item>規則集變化 → 重新編譯 → 按差異在一個事務中增刪過濾器；</item>
/// <item>未知連接被默認阻斷過濾器攔下 → WFP 網絡事件 → 核心觸發詢問（「先阻斷後詢問」，應用重試即可連上）；</item>
/// <item>DNS-Client ETW 事件 → 域名規則展開為 IP 過濾器。</item>
/// </list>
/// 所有過濾器建在動態會話中：服務退出時由系統自動刪除，網絡恢復暢通。
/// </summary>
internal sealed partial class WfpEngine : INetworkEngine, IDiagnosticsContributor
{
    private const int MaxRecordedFilterFailures = 50;

    private static readonly TimeSpan RecompileDebounce = TimeSpan.FromMilliseconds(150);
    private static readonly TimeSpan MaintenanceInterval = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan DropDedupWindow = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan DnsTtl = TimeSpan.FromMinutes(5);

    private readonly SettingsService _settings;
    private readonly DevicePathResolver _paths;
    private readonly DomainIndex _domains;
    private readonly TimeProvider _time;
    private readonly ILogger<WfpEngine> _logger;
    private readonly Channel<bool> _recompile = Channel.CreateBounded<bool>(new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropWrite });
    private readonly Dictionary<string, (FilterSpec Spec, ulong Id)> _installed = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<(string App, IPAddress Remote, int Port, byte Protocol), DateTimeOffset> _recentDrops = new();

    /// <summary>阻斷事件中觀測到的原始應用 ID（DOS 路徑 → 字節），與 WFP 匹配時使用的值完全一致。</summary>
    private readonly ConcurrentDictionary<string, byte[]> _observedAppIds = new(StringComparer.OrdinalIgnoreCase);

    private IEngineHost? _host;
    private WfpSession? _session;
    private DnsClientMonitor? _dns;
    private Traffic.NetworkTrafficMonitor? _traffic;
    private nint _eventsHandle;
    private GCHandle _self;
    private uint? _previousCollectOption;
    private CancellationTokenSource? _cts;
    private Task? _loop;
    private long _nextFlowId;

    // 網絡事件回調在 WFP 線程上運行，讀取這些不可變快照
    private FrozenSet<ulong> _defaultBlockIds = FrozenSet<ulong>.Empty;
    private FrozenSet<ulong> _inboundDefaultBlockIds = FrozenSet<ulong>.Empty;
    private FrozenSet<ulong> _ruleBlockIds = FrozenSet<ulong>.Empty;
    private DomainPattern[] _domainPatterns = [];

    // 診斷信息：後台循環寫入不可變快照，導出診斷包時在其他線程讀取
    private (FilterSpec Spec, ulong Id)[] _installedSnapshot = [];
    private SkippedRule[] _lastSkipped = [];
    private readonly ConcurrentQueue<string> _filterFailures = new();
    private DateTimeOffset? _lastAppliedAt;
    private long _dropsSeen;
    private long _dropsReported;

    private readonly IProcessInfoProvider _processes;

    public WfpEngine(SettingsService settings, DevicePathResolver paths, IProcessInfoProvider processes, TimeProvider time, ILogger<WfpEngine> logger)
    {
        _processes = processes;
        _settings = settings;
        _paths = paths;
        _time = time;
        _logger = logger;
        _domains = new DomainIndex(time);
    }

    public string Name => "WFP";

    public EngineCapabilities Capabilities => EngineCapabilities.RulePush | EngineCapabilities.TrafficStats | EngineCapabilities.KillConnection;

    public ValueTask<bool> KillConnectionAsync(ConnectionKey key, CancellationToken cancellationToken) =>
        WindowsConnectionKiller.KillAsync(key, cancellationToken);

    public async Task StartAsync(IEngineHost host, CancellationToken cancellationToken)
    {
        _host = host;
        try
        {
            StartCore(host);
        }
        catch
        {
            // 部分啟動（例如過濾器已下發但事件訂閱失敗）會讓未知連接被靜默阻斷而不彈窗：
            // 必須完整清理，讓網絡回到未過濾狀態，再把故障報告給核心
            await StopAsync(CancellationToken.None);
            throw;
        }

        LogStarted(_logger, _installed.Count);
    }

    private void StartCore(IEngineHost host)
    {
        _session = WfpSession.Open("NetworkControl");
        _session.AppIdFallback = path => _observedAppIds.GetValueOrDefault(path) ?? _paths.BuildAppId(path);
        _self = GCHandle.Alloc(this);

        // 1. 先開啟並訂閱網絡事件，再下發過濾器：保證默認阻斷生效後的每條連接都能觸發詢問。
        //    記錄原來的系統設置，停止時恢復。
        var previous = WfpSession.GetGlobalOption(FwpmEngineOption.CollectNetEvents);
        if (previous != 1)
        {
            WfpSession.SetGlobalOption(FwpmEngineOption.CollectNetEvents, 1);
            // 只有本服務改過的設置才在停止時恢復
            _previousCollectOption = previous ?? 0;
        }
        SubscribeNetEvents(_session);

        // 2. 下發一次完整的過濾器
        Recompile();

        // 3. DNS 事件（失敗不致命：只影響域名規則）
        try
        {
            _dns = new DnsClientMonitor(_logger);
            _dns.QueryCompleted += OnDnsQuery;
            _dns.Start();
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or InvalidOperationException or COMException)
        {
            host.OnEngineFault(new EngineFault("wfp.dns", $"DNS monitoring unavailable: {ex.Message}", IsFatal: false, ex));
            _dns = null;
        }

        // 4. 流量統計（失敗不致命：只影響用量顯示）
        try
        {
            _traffic = new Traffic.NetworkTrafficMonitor(_processes, _time, _logger);
            _traffic.Start(host);
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or InvalidOperationException or COMException)
        {
            host.OnEngineFault(new EngineFault("wfp.traffic", $"Traffic statistics unavailable: {ex.Message}", IsFatal: false, ex));
            _traffic?.Dispose();
            _traffic = null;
        }

        host.Rules.RuleSetChanged += OnRuleSetChanged;
        _settings.Changed += OnSettingsChanged;

        _cts = new CancellationTokenSource();
        _loop = Task.Run(() => RunAsync(_cts.Token), CancellationToken.None);
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        if (_host is not null)
        {
            _host.Rules.RuleSetChanged -= OnRuleSetChanged;
        }

        _settings.Changed -= OnSettingsChanged;

        // 可重複調用：DI 容器會對同一實例（具體類型 + INetworkEngine 兩種註冊）多次調用 DisposeAsync
        var cts = Interlocked.Exchange(ref _cts, null);
        if (cts is not null)
        {
            await cts.CancelAsync();
        }

        var loop = Interlocked.Exchange(ref _loop, null);
        if (loop is not null)
        {
            try
            {
                await loop;
            }
            catch (OperationCanceledException)
            {
                // 正常停止
            }
        }

        cts?.Dispose();

        _dns?.Dispose();
        _dns = null;
        _traffic?.Dispose();
        _traffic = null;

        if (_session is not null)
        {
            if (_eventsHandle != 0)
            {
                FwpNative.FwpmNetEventUnsubscribe0(_session.Handle, _eventsHandle);
                _eventsHandle = 0;
            }

            if (_previousCollectOption is { } previous)
            {
                try
                {
                    WfpSession.SetGlobalOption(FwpmEngineOption.CollectNetEvents, previous);
                    _previousCollectOption = null;
                }
                catch (WfpException ex)
                {
                    LogRestoreFailed(_logger, ex);
                }
            }

            // 關閉動態會話：本會話創建的所有過濾器由系統刪除
            _session.Dispose();
            _session = null;
        }

        _installed.Clear();
        _installedSnapshot = [];
        if (_self.IsAllocated)
        {
            _self.Free();
        }
    }

    public async ValueTask DisposeAsync() => await StopAsync(CancellationToken.None);

    private unsafe void SubscribeNetEvents(WfpSession session)
    {
        var subscription = new FwpmNetEventSubscription0();
        WfpSession.Check("FwpmNetEventSubscribe0",
            FwpNative.FwpmNetEventSubscribe0(session.Handle, &subscription, &OnNetEvent, GCHandle.ToIntPtr(_self), out _eventsHandle));
    }

    private void OnRuleSetChanged(object? sender, RuleSetChangedEventArgs e) => _recompile.Writer.TryWrite(true);

    private void OnSettingsChanged(object? sender, EventArgs e) => _recompile.Writer.TryWrite(true);

    private async Task RunAsync(CancellationToken ct)
    {
        using var maintenance = new PeriodicTimer(MaintenanceInterval, _time);
        var maintenanceTick = maintenance.WaitForNextTickAsync(ct).AsTask();
        var signal = _recompile.Reader.WaitToReadAsync(ct).AsTask();

        while (!ct.IsCancellationRequested)
        {
            var completed = await Task.WhenAny(signal, maintenanceTick);
            if (completed == maintenanceTick)
            {
                if (!await maintenanceTick)
                {
                    break;
                }

                maintenanceTick = maintenance.WaitForNextTickAsync(ct).AsTask();
                PruneDrops();
                if (!_domains.Prune())
                {
                    continue;
                }
            }
            else
            {
                await signal;
                // 去抖：連續的規則變更合併成一次編譯
                await Task.Delay(RecompileDebounce, _time, ct);
                while (_recompile.Reader.TryRead(out _))
                {
                }

                signal = _recompile.Reader.WaitToReadAsync(ct).AsTask();
            }

            try
            {
                Recompile();
            }
            catch (Exception ex) when (ex is WfpException or InvalidOperationException)
            {
                LogApplyFailed(_logger, ex);
                _host?.OnEngineFault(new EngineFault("wfp.apply", ex.Message, IsFatal: false, ex));
            }
        }
    }

    /// <summary>編譯當前規則集並按差異下發。只在後台循環（或啟動時）調用，不需要加鎖。</summary>
    private void Recompile()
    {
        var host = _host ?? throw new InvalidOperationException("Engine is not started.");
        var session = _session ?? throw new InvalidOperationException("WFP session is closed.");
        var rules = host.Rules.Current.Rules;

        _domainPatterns = rules
            .Select(r => r.Conditions.Remote)
            .OfType<DomainTarget>()
            .Select(d => DomainPattern.Parse(d.Pattern))
            .ToArray();

        var result = RuleCompiler.Compile(new CompileInput
        {
            Rules = rules,
            Mode = host.CurrentMode,
            AllowIcmp = _settings.Current.AllowIcmpByDefault,
            InboundDefaultDeny = _settings.Current.InboundDefault == InboundDefault.Deny,
            SelfPath = Environment.ProcessPath,
            SystemServiceHostPath = Path.Combine(Environment.SystemDirectory, "svchost.exe"),
            ResolveDomain = _domains.Resolve,
            ExpandPath = PathExpander.Expand,
        });

        _lastSkipped = [.. result.Skipped];
        foreach (var skipped in result.Skipped)
        {
            LogRuleSkipped(_logger, skipped.RuleId, skipped.Reason);
        }

        var desired = result.Filters.GroupBy(f => f.Key).ToDictionary(g => g.Key, g => g.First(), StringComparer.Ordinal);
        var remove = _installed.Where(kv => !desired.ContainsKey(kv.Key)).ToList();
        var add = desired.Where(kv => !_installed.ContainsKey(kv.Key)).Select(kv => kv.Value).ToList();
        if (remove.Count == 0 && add.Count == 0)
        {
            return;
        }

        var added = session.Apply(remove.Select(kv => kv.Value.Id).ToArray(), add, OnFilterFailed);
        foreach (var kv in remove)
        {
            _installed.Remove(kv.Key);
        }

        foreach (var (spec, id) in added)
        {
            _installed[spec.Key] = (spec, id);
        }

        _defaultBlockIds = _installed.Values.Where(f => f.Spec.Role == FilterRole.DefaultBlock).Select(f => f.Id).ToFrozenSet();
        _inboundDefaultBlockIds = _installed.Values.Where(f => f.Spec.Role == FilterRole.InboundDefaultBlock).Select(f => f.Id).ToFrozenSet();
        _ruleBlockIds = _installed.Values.Where(f => f.Spec is { Role: FilterRole.Rule or FilterRole.BlockAll, Permit: false }).Select(f => f.Id).ToFrozenSet();
        _installedSnapshot = [.. _installed.Values];
        _lastAppliedAt = _time.GetUtcNow();
        LogApplied(_logger, added.Count, remove.Count, _installed.Count);
    }

    private void OnFilterFailed(FilterSpec spec, Exception exception)
    {
        LogFilterSkipped(_logger, exception, spec.Name, spec.AppPath);
        _filterFailures.Enqueue($"{_time.GetUtcNow():u} {spec.Name} ({spec.AppPath ?? "any app"}): {exception.Message}");
        while (_filterFailures.Count > MaxRecordedFilterFailures && _filterFailures.TryDequeue(out _))
        {
        }
    }

    private void OnDnsQuery(DnsQueryResult query)
    {
        _host?.OnDnsObserved(new DnsObservation(query.Name, query.Addresses, DnsTtl));
        if (_domains.Observe(query.Name, query.Addresses) && DomainIndex.MatchesAny(query.Name, _domainPatterns))
        {
            // 有域名規則依賴這個域名，且出現了新的 IP：重新編譯
            _recompile.Writer.TryWrite(true);
        }
    }

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvStdcall)])]
    private static unsafe void OnNetEvent(nint context, FwpmNetEvent1* evt)
    {
        try
        {
            if (evt is null || evt->Type != FwpmNetEventType.ClassifyDrop || evt->Detail == 0
                || GCHandle.FromIntPtr(context).Target is not WfpEngine engine)
            {
                return;
            }

            engine.HandleDrop(evt);
        }
        catch (Exception)
        {
            // 回調中的異常不能傳回本機代碼
        }
    }

    private unsafe void HandleDrop(FwpmNetEvent1* evt)
    {
        Interlocked.Increment(ref _dropsSeen);
        var drop = (FwpmNetEventClassifyDrop1*)evt->Detail;
        BlockReason reason;
        if (_inboundDefaultBlockIds.Contains(drop->FilterId))
        {
            reason = BlockReason.InboundDefault;
        }
        else if (_defaultBlockIds.Contains(drop->FilterId))
        {
            reason = BlockReason.DefaultBlock;
        }
        else if (_ruleBlockIds.Contains(drop->FilterId))
        {
            reason = BlockReason.UserRule;
        }
        else
        {
            // 其他防火牆（例如 Windows 防火牆）的阻斷，與本產品無關
            return;
        }

        if (TryCreateFlow(&evt->Header, drop->MsFwpDirection) is not { } flow)
        {
            return;
        }

        var key = (flow.ExecutablePath ?? "", flow.Remote.Address, flow.Remote.Port, (byte)flow.Protocol);
        var now = _time.GetUtcNow();
        if (_recentDrops.TryGetValue(key, out var last) && now - last < DropDedupWindow)
        {
            return;
        }

        _recentDrops[key] = now;
        Interlocked.Increment(ref _dropsReported);
        _host?.OnFlowBlocked(flow, reason);
    }

    private unsafe FlowInfo? TryCreateFlow(FwpmNetEventHeader1* header, uint direction)
    {
        const uint required = FwpConstants.NetEventFlagRemoteAddrSet | FwpConstants.NetEventFlagIpVersionSet;
        if ((header->Flags & required) != required)
        {
            return null;
        }

        IPAddress remote;
        IPAddress local;
        if (header->IpVersion == FwpIpVersion.V4)
        {
            remote = FromHostOrder(header->RemoteAddr.V4);
            local = (header->Flags & FwpConstants.NetEventFlagLocalAddrSet) != 0 ? FromHostOrder(header->LocalAddr.V4) : IPAddress.Any;
        }
        else
        {
            remote = new IPAddress(new ReadOnlySpan<byte>(header->RemoteAddr.V6, 16));
            local = (header->Flags & FwpConstants.NetEventFlagLocalAddrSet) != 0 ? new IPAddress(new ReadOnlySpan<byte>(header->LocalAddr.V6, 16)) : IPAddress.IPv6Any;
        }

        string? path = null;
        if ((header->Flags & FwpConstants.NetEventFlagAppIdSet) != 0 && header->AppId.Data != 0 && header->AppId.Size >= 2)
        {
            var devicePath = new string((char*)header->AppId.Data, 0, (int)(header->AppId.Size / 2)).TrimEnd('\0');
            path = _paths.ToDosPath(devicePath);
            if (!_observedAppIds.ContainsKey(path) && _observedAppIds.Count < 4096)
            {
                _observedAppIds[path] = new ReadOnlySpan<byte>((void*)header->AppId.Data, (int)header->AppId.Size).ToArray();
            }
        }

        string? user = null;
        if ((header->Flags & FwpConstants.NetEventFlagUserIdSet) != 0 && header->UserId != 0)
        {
            user = new SecurityIdentifier(header->UserId).Value;
        }

        var protocol = (header->Flags & FwpConstants.NetEventFlagIpProtocolSet) != 0 ? (L4Protocol)header->IpProtocol : L4Protocol.Other;
        return new FlowInfo
        {
            FlowId = (ulong)Interlocked.Increment(ref _nextFlowId),
            Direction = direction == 1 ? Direction.Inbound : Direction.Outbound,
            Protocol = Enum.IsDefined(protocol) ? protocol : L4Protocol.Other,
            Local = new IPEndPoint(local, (header->Flags & FwpConstants.NetEventFlagLocalPortSet) != 0 ? header->LocalPort : 0),
            Remote = new IPEndPoint(remote, (header->Flags & FwpConstants.NetEventFlagRemotePortSet) != 0 ? header->RemotePort : 0),
            ExecutablePath = path,
            UserId = user,
            Timestamp = _time.GetUtcNow(),
            Origin = FlowOrigin.BlockedEvent,
        };
    }

    /// <summary>WFP 事件中的 IPv4 地址是主機字節序。</summary>
    internal static IPAddress FromHostOrder(uint address) =>
        new([(byte)(address >> 24), (byte)(address >> 16), (byte)(address >> 8), (byte)address]);

    private void PruneDrops()
    {
        var cutoff = _time.GetUtcNow() - DropDedupWindow;
        foreach (var (key, at) in _recentDrops)
        {
            if (at < cutoff)
            {
                _recentDrops.TryRemove(key, out _);
            }
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "WFP 引擎已啟動，已下發 {Count} 個過濾器")]
    private static partial void LogStarted(ILogger logger, int count);

    [LoggerMessage(Level = LogLevel.Debug, Message = "過濾器已更新：新增 {Added}，刪除 {Removed}，共 {Total}")]
    private static partial void LogApplied(ILogger logger, int added, int removed, int total);

    [LoggerMessage(Level = LogLevel.Debug, Message = "規則 {RuleId} 暫不下發：{Reason}")]
    private static partial void LogRuleSkipped(ILogger logger, Guid ruleId, string reason);

    [LoggerMessage(Level = LogLevel.Warning, Message = "過濾器 {Name}（{Path}）無法下發，已跳過")]
    private static partial void LogFilterSkipped(ILogger logger, Exception exception, string name, string? path);

    [LoggerMessage(Level = LogLevel.Error, Message = "下發 WFP 過濾器失敗，保留上一次的過濾器")]
    private static partial void LogApplyFailed(ILogger logger, Exception exception);

    [LoggerMessage(Level = LogLevel.Warning, Message = "恢復 WFP 網絡事件收集設置失敗")]
    private static partial void LogRestoreFailed(ILogger logger, Exception exception);
}
