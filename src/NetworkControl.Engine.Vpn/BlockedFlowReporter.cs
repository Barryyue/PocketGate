using System.Net;
using System.Threading.Channels;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;
using NetworkControl.Engine.Vpn.Dns;
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
    private long _dnsAnswered;
    private long _systemUid;

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

    /// <summary>假 DNS 回答的查詢數。</summary>
    public long DnsAnswered => Interlocked.Read(ref _dnsAnswered);

    internal void AddPacket() => Interlocked.Increment(ref _packets);

    internal void AddDnsAnswered() => Interlocked.Increment(ref _dnsAnswered);

    /// <summary>來自系統核心 UID（沒有軟件包、無法按包排除，例如 DNS 解析服務的 DoT 探測）的嘗試數：只計數，不上報。</summary>
    public long SystemUid => Interlocked.Read(ref _systemUid);

    internal void AddSystemUid() => Interlocked.Increment(ref _systemUid);

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
/// 發往 <see cref="VpnTunnelConfig.DnsServer"/> 的 DNS 查詢在讀線程上直接用假地址回答。
/// </summary>
internal sealed partial class BlockedFlowReporter : IPacketSink
{
    private const int QueueCapacity = 512;
    private const int MaxDedupEntries = 4096;

    /// <summary>假 DNS 回答的 TTL：盡量短，用戶允許後應用（改走真實網絡）很快就會重新解析。</summary>
    private const uint FakeDnsTtlSeconds = 1;

    private readonly IConnectionOwnerResolver _owners;
    private readonly IInstalledAppCatalog _catalog;
    private readonly Func<VpnRoutingPlan> _plan;
    private readonly TimeProvider _time;
    private readonly TimeSpan _dedupWindow;
    private readonly ILogger _logger;
    private readonly Channel<PacketInfo> _queue = Channel.CreateBounded<PacketInfo>(
        new BoundedChannelOptions(QueueCapacity) { FullMode = BoundedChannelFullMode.DropWrite, SingleReader = true });

    private readonly Dictionary<DedupKey, DateTimeOffset> _recent = [];
    private readonly FakeDnsPool _fakeDns;
    private long _nextFlowId;

    public BlockedFlowReporter(
        IConnectionOwnerResolver owners,
        IInstalledAppCatalog catalog,
        Func<VpnRoutingPlan> plan,
        VpnEngineCounters counters,
        TimeProvider time,
        TimeSpan dedupWindow,
        ILogger logger,
        FakeDnsPool? fakeDns = null)
    {
        _fakeDns = fakeDns ?? new FakeDnsPool();
        _owners = owners;
        _catalog = catalog;
        _plan = plan;
        Counters = counters;
        _time = time;
        _dedupWindow = dedupWindow;
        _logger = logger;
    }

    public VpnEngineCounters Counters { get; }

    public void OnPacket(ReadOnlySpan<byte> packet, IPacketWriter writer)
    {
        Counters.AddPacket();
        if (!IpPacketParser.TryParse(packet, out var info))
        {
            Counters.AddUnparsed();
            return;
        }

        if (info.Protocol == L4Protocol.Udp && info.Destination.Port == 53 && info.Destination.Address.Equals(VpnTunnelConfig.DnsServer))
        {
            AnswerDns(packet, info, writer);
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

        if (VpnRoutingPlanner.IsSystemUid(uid))
        {
            Counters.AddSystemUid();
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
            // 連向假 DNS 地址：反查出應用想訪問的域名
            EngineHostname = _fakeDns.TryGetName(packet.Destination.Address, out var hostname) ? hostname : null,
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

    /// <summary>
    /// 假 DNS：A 查詢返回地址池中分配給該域名的地址，其他類型返回沒有記錄（應用會退回 IPv4）。
    /// 只有被阻斷的應用會走到這裡（允許的應用不經過 VPN）。
    /// </summary>
    private void AnswerDns(ReadOnlySpan<byte> packet, in PacketInfo info, IPacketWriter writer)
    {
        var message = packet.Slice(info.PayloadOffset, info.PayloadLength);
        if (!DnsWire.TryParseQuery(message, out var query) || query.Name.Length == 0)
        {
            return;
        }

        var address = query.Type == DnsWire.TypeA ? _fakeDns.Allocate(query.Name) : null;
        var response = DnsWire.BuildResponse(query, message.Slice(12, query.QuestionLength), address, FakeDnsTtlSeconds);
        writer.Write(IpPacketBuilder.Udp4(info.Destination, info.Source, response));
        Counters.AddDnsAnswered();
    }

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
