using System.Net;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;

namespace NetworkControl.Engine.Fake;

/// <summary>一次模擬連接的請求。</summary>
public sealed record SimulatedFlowRequest
{
    public required string AppKey { get; init; }

    /// <summary>目標域名；設置後會先模擬一次 DNS 解析。</summary>
    public string? Hostname { get; init; }

    /// <summary>目標 IP；為空時根據域名生成一個穩定的虛擬地址。</summary>
    public IPAddress? Address { get; init; }

    public ushort Port { get; init; } = 443;

    public L4Protocol Protocol { get; init; } = L4Protocol.Tcp;

    public Direction Direction { get; init; } = Direction.Outbound;
}

/// <summary>模擬連接的結果。</summary>
public sealed record SimulatedFlowResult(ulong FlowId, VerdictAction Action, IPEndPoint Remote, int Pid);

/// <summary>模擬器接口：供開發界面和自動化測試注入連接。</summary>
public interface INetworkSimulator
{
    IReadOnlyCollection<FakeAppDefinition> Apps { get; }

    Task<SimulatedFlowResult> SimulateAsync(SimulatedFlowRequest request, CancellationToken cancellationToken);

    bool ExitApp(string appKey);
}

/// <summary>
/// 模擬引擎：行為上等同於一個支持「掛起判決」的真實引擎（Linux NFQUEUE / macOS NE），
/// 但連接來自 <see cref="SimulateAsync"/> 而不是操作系統。
/// </summary>
public sealed class FakeNetworkEngine(FakeProcessTable processes, TimeProvider time) : INetworkEngine, INetworkSimulator
{
    private IEngineHost? _host;
    private long _nextFlowId;
    private int _nextLocalPort = 49_152;

    public string Name => "Fake";

    public EngineCapabilities Capabilities =>
        EngineCapabilities.PendingVerdict | EngineCapabilities.ProvidesPid | EngineCapabilities.ProvidesHostname | EngineCapabilities.Inbound;

    public IReadOnlyCollection<FakeAppDefinition> Apps => processes.Apps;

    public Task StartAsync(IEngineHost host, CancellationToken cancellationToken)
    {
        _host = host;
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _host = null;
        return Task.CompletedTask;
    }

    public async Task<SimulatedFlowResult> SimulateAsync(SimulatedFlowRequest request, CancellationToken cancellationToken)
    {
        var host = _host ?? throw new InvalidOperationException("模擬引擎尚未啟動。");
        var process = processes.GetOrStart(request.AppKey);
        var address = request.Address ?? AddressFor(request.Hostname ?? request.AppKey);

        if (request.Hostname is { Length: > 0 } hostname)
        {
            // 真實引擎中，DNS 響應總是先於連接被觀測到
            host.OnDnsObserved(new DnsObservation(hostname, [address], TimeSpan.FromMinutes(5), process.Process));
        }

        var localPort = Interlocked.Increment(ref _nextLocalPort);
        if (localPort > 65_000)
        {
            Interlocked.Exchange(ref _nextLocalPort, 49_152);
        }

        var flow = new FlowInfo
        {
            FlowId = (ulong)Interlocked.Increment(ref _nextFlowId),
            Direction = request.Direction,
            Protocol = request.Protocol,
            Local = new IPEndPoint(address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6 ? IPAddress.Parse("fd00::10") : IPAddress.Parse("192.168.1.10"), localPort),
            Remote = new IPEndPoint(address, request.Port),
            Process = process.Process,
            ExecutablePath = process.ExecutablePath,
            UserId = process.UserId,
            Timestamp = time.GetUtcNow(),
            Origin = FlowOrigin.Pending,
        };

        var action = await host.DecideAsync(flow).AsTask().WaitAsync(cancellationToken);
        return new SimulatedFlowResult(flow.FlowId, action, flow.Remote, process.Process.Pid);
    }

    public bool ExitApp(string appKey) => processes.Exit(appKey);

    public ValueTask DisposeAsync() => ValueTask.CompletedTask;

    /// <summary>根據名稱生成穩定的虛擬公網地址（203.0.113.0/24 是文檔保留網段）。</summary>
    private static IPAddress AddressFor(string name)
    {
        // 不能用 string.GetHashCode：它在每個進程中隨機化，地址需要跨重啟穩定
        var hash = 2166136261u;
        foreach (var c in name.ToUpperInvariant())
        {
            hash = (hash ^ c) * 16777619u;
        }

        return new IPAddress([203, 0, 113, (byte)(1 + (hash % 254))]);
    }
}
