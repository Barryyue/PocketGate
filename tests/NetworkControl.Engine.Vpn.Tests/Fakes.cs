using System.Collections.Concurrent;
using System.Net;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Rules;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Vpn.Tests;

internal sealed class FakeAppCatalog(params InstalledApp[] apps) : IInstalledAppCatalog
{
    private readonly Lock _gate = new();
    private List<InstalledApp> _apps = [.. apps];

    public event EventHandler? Changed;

    public IReadOnlyList<InstalledApp> GetApps()
    {
        lock (_gate)
        {
            return [.. _apps];
        }
    }

    public IReadOnlyList<string> GetPackagesForUid(int uid) => [.. GetApps().Where(a => a.Uid == uid).Select(a => a.PackageName)];

    public InstalledApp? Find(string packageName) => GetApps().FirstOrDefault(a => a.PackageName == packageName);

    public void Install(InstalledApp app)
    {
        lock (_gate)
        {
            _apps = [.. _apps.Where(a => a.PackageName != app.PackageName), app];
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }
}

/// <summary>按本地端口決定報文屬於哪個 UID。</summary>
internal sealed class FakeOwnerResolver : IConnectionOwnerResolver
{
    public ConcurrentDictionary<int, int> UidByLocalPort { get; } = new();

    public int? GetOwnerUid(L4Protocol protocol, IPEndPoint local, IPEndPoint remote) =>
        UidByLocalPort.TryGetValue(local.Port, out var uid) ? uid : null;
}

internal sealed class FakeTunnel : IVpnTunnel
{
    private IPacketSink? _sink;

    public event EventHandler? StateChanged;

    public VpnTunnelState State { get; private set; }

    public string? LastError { get; private set; }

    public bool IsPrepared { get; set; } = true;

    public ConcurrentQueue<VpnTunnelConfig> Applied { get; } = new();

    public VpnTunnelConfig? Current => Applied.LastOrDefault();

    public int CloseCount { get; private set; }

    public Task ApplyAsync(VpnTunnelConfig config, IPacketSink sink, CancellationToken cancellationToken)
    {
        _sink = sink;
        Applied.Enqueue(config);
        SetState(VpnTunnelState.Active);
        return Task.CompletedTask;
    }

    public Task CloseAsync(CancellationToken cancellationToken)
    {
        CloseCount++;
        _sink = null;
        SetState(VpnTunnelState.Closed);
        return Task.CompletedTask;
    }

    /// <summary>寫回虛擬網卡的報文。</summary>
    public RecordingWriter Written { get; } = new();

    /// <summary>模擬從虛擬網卡讀到一個報文。</summary>
    public void Receive(byte[] packet) => (_sink ?? throw new InvalidOperationException("虛擬網卡未建立。")).OnPacket(packet, Written);

    public void Revoke()
    {
        LastError = "被其他 VPN 取代";
        _sink = null;
        SetState(VpnTunnelState.Revoked);
    }

    private void SetState(VpnTunnelState state)
    {
        State = state;
        StateChanged?.Invoke(this, EventArgs.Empty);
    }
}

/// <summary>記錄寫回虛擬網卡的報文。</summary>
internal sealed class RecordingWriter : IPacketWriter
{
    public ConcurrentQueue<byte[]> Packets { get; } = new();

    public void Write(ReadOnlySpan<byte> packet) => Packets.Enqueue(packet.ToArray());
}

/// <summary>記錄上報事件的引擎宿主。</summary>
internal sealed class RecordingEngineHost : IEngineHost
{
    public List<(FlowInfo Flow, BlockReason Reason)> Blocked { get; } = [];

    public List<EngineFault> Faults { get; } = [];

    public RuleEngine Rules { get; } = new();

    public FilterMode CurrentMode { get; set; } = FilterMode.Ask;

    public FailPolicy FailPolicy => FailPolicy.FailOpen;

    public ValueTask<VerdictAction> DecideAsync(FlowInfo flow) => ValueTask.FromResult(VerdictAction.Deny);

    public void OnFlowBlocked(FlowInfo flow, BlockReason reason) => Blocked.Add((flow, reason));

    public void OnDnsObserved(DnsObservation observation)
    {
    }

    public void OnTraffic(ReadOnlySpan<TrafficSample> samples)
    {
    }

    public void OnEngineFault(EngineFault fault) => Faults.Add(fault);
}

internal static class Eventually
{
    public static async Task True(Func<bool> condition, string because)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(5));
        while (!condition())
        {
            try
            {
                await Task.Delay(10, timeout.Token);
            }
            catch (OperationCanceledException) when (!TestContext.Current.CancellationToken.IsCancellationRequested)
            {
                Assert.Fail($"等待超時：{because}");
            }
        }
    }
}
