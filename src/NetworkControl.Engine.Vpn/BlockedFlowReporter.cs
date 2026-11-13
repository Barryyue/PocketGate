using System.Net;
using System.Threading.Channels;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;
using NetworkControl.Engine.Vpn.Packets;
using NetworkControl.Engine.Vpn.Platform;
using NetworkControl.Engine.Vpn.Routing;

namespace NetworkControl.Engine.Vpn;

/// <summary>引擎計數器（診斷用）。</summary>
public sealed class VpnEngineCounters
{
    private long _packets;
    private long _unparsed;
    private long _attempts;
    private long _queueFull;
    private long _unknownOwner;
    private long _deduplicated;
    private long _reported;

    /// <summary>從虛擬網卡讀到的報文數。</summary>
    public long Packets => Interlocked.Read(ref _packets);

    /// <summary>無法解析（或非首個分片）的報文數。</summary>
    public long Unparsed => Interlocked.Read(ref _unparsed);

    /// <summary>新的聯網嘗試（TCP SYN、UDP）數。</summary>
    public long Attempts => Interlocked.Read(ref _attempts);

    /// <summary>處理隊列已滿而未處理的嘗試數。</summary>
    public long QueueFull => Interlocked.Read(ref _queueFull);

    /// <summary>查不到所屬 UID 的嘗試數。</summary>
    public long UnknownOwner => Interlocked.Read(ref _unknownOwner);

    /// <summary>去重窗口內的重複嘗試數。</summary>
    public long Deduplicated => Interlocked.Read(ref _deduplicated);

    /// <summary>上報給核心的阻斷事件數。</summary>
    public long Reported => Interlocked.Read(ref _reported);

    internal void AddPacket() => Interlocked.Increment(ref _packets);

    internal void AddUnparsed() => Interlocked.Increment(ref _unparsed);

    internal void AddAttempt() => Interlocked.Increment(ref _attempts);

    internal void AddQueueFull() => Interlocked.Increment(ref _queueFull);

    internal void AddUnknownOwner() => Interlocked.Increment(ref _unknownOwner);

    internal void AddDeduplicated() => Interlocked.Increment(ref _deduplicated);

    internal void AddReported() => Interlocked.Increment(ref _reported);
}

/// <summary>
/// 把虛擬網卡中的報文轉換成核心的阻斷事件（docs/android.md §5.5）：
/// 讀線程只解析和入隊；後台任務查詢 UID、去重、構造 <see cref="FlowInfo"/> 並調用 <see cref="IEngineHost.OnFlowBlocked"/>。
/// </summary>
internal sealed partial class BlockedFlowReporter : IPacketSink
{
    private const int QueueCapacity = 512;
    private const int MaxDedupEntries = 4096;

    private readonly IConnectionOwnerResolver _owners;
    private readonly IInstalledAppCatalog _catalog;
    private readonly Func<VpnRoutingPlan> _plan;
    private readonly TimeProvider _time;
    private readonly TimeSpan _dedupWindow;
    private readonly ILogger _logger;
    private readonly Channel<PacketInfo> _queue = Channel.CreateBounded<PacketInfo>(
        new BoundedChannelOptions(QueueCapacity) { FullMode = BoundedChannelFullMode.DropWrite, SingleReader = true });

    private readonly Dictionary<DedupKey, DateTimeOffset> _recent = [];
    private long _nextFlowId;

    public BlockedFlowReporter(
        IConnectionOwnerResolver owners,
        IInstalledAppCatalog catalog,
        Func<VpnRoutingPlan> plan,
        VpnEngineCounters counters,
        TimeProvider time,
        TimeSpan dedupWindow,
        ILogger logger)
    {
        _owners = owners;
        _catalog = catalog;
        _plan = plan;
        Counters = counters;
        _time = time;
        _dedupWindow = dedupWindow;
        _logger = logger;
    }

    public VpnEngineCounters Counters { get; }

    public void OnPacket(ReadOnlySpan<byte> packet)
    {
        Counters.AddPacket();
        if (!IpPacketParser.TryParse(packet, out var info))
        {
            Counters.AddUnparsed();
            return;
        }

        if (!info.IsConnectionAttempt)
        {
            return;
        }

        Counters.AddAttempt();
        if (!_queue.Writer.TryWrite(info))
        {
            Counters.AddQueueFull();
        }
    }

    /// <summary>處理隊列，直到取消。</summary>
    public async Task RunAsync(IEngineHost host, CancellationToken cancellationToken)
    {
        try
        {
            await foreach (var packet in _queue.Reader.ReadAllAsync(cancellationToken))
            {
                try
                {
                    Process(host, packet);
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    LogProcessFailed(_logger, ex);
                }
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // 停止
        }
    }

    /// <summary>處理一個聯網嘗試；返回上報的連接（被忽略時返回 null）。</summary>
    internal FlowInfo? Process(IEngineHost host, in PacketInfo packet)
    {
        if (_owners.GetOwnerUid(packet.Protocol, packet.Source, packet.Destination) is not { } uid)
        {
            Counters.AddUnknownOwner();
            return null;
        }

        var now = _time.GetUtcNow();
        var key = new DedupKey(uid, packet.Protocol, packet.Destination.Address, packet.Destination.Port);
        if (_recent.TryGetValue(key, out var last) && now - last < _dedupWindow)
        {
            Counters.AddDeduplicated();
            return null;
        }

        if (_recent.Count >= MaxDedupEntries)
        {
            PruneExpired(now);
        }

        _recent[key] = now;

        var plan = _plan();
        var routing = plan.Find(uid);
        var packages = _catalog.GetPackagesForUid(uid);
        var package = routing?.PackageName ?? packages.Order(StringComparer.Ordinal).FirstOrDefault();

        var flow = new FlowInfo
        {
            FlowId = (ulong)Interlocked.Increment(ref _nextFlowId),
            Direction = Direction.Outbound,
            Protocol = packet.Protocol,
            Local = packet.Source,
            Remote = packet.Destination,
            ExecutablePath = package is null ? PackageAppPath.ForUid(uid) : PackageAppPath.ForPackage(package),
            Timestamp = now,
            Origin = FlowOrigin.BlockedEvent,
        };

        // 已被規則拒絕的應用只記錄日誌；其他情況（未決定、應用剛安裝還沒有進入計劃）交給核心判斷是否詢問
        var reason = routing is { Decision: AppRoutingDecision.Block, Reason: AppRoutingReason.Rule }
            ? BlockReason.UserRule
            : BlockReason.DefaultBlock;

        host.OnFlowBlocked(flow, reason);
        Counters.AddReported();
        return flow;
    }

    public void Complete() => _queue.Writer.TryComplete();

    private void PruneExpired(DateTimeOffset now)
    {
        foreach (var (key, at) in _recent.ToArray())
        {
            if (now - at >= _dedupWindow)
            {
                _recent.Remove(key);
            }
        }

        if (_recent.Count >= MaxDedupEntries)
        {
            _recent.Clear();
        }
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "處理被阻斷的聯網嘗試失敗")]
    private static partial void LogProcessFailed(ILogger logger, Exception exception);

    private readonly record struct DedupKey(int Uid, L4Protocol Protocol, IPAddress Address, int Port);
}
