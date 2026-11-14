using System.Net;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;

namespace NetworkControl.Engine.Fake;

/// <summary>為開發界面提供可預期的虛擬連線快照。</summary>
public sealed class FakeConnectionTable(FakeProcessTable processes) : IConnectionTable
{
    public IReadOnlyList<ActiveConnection> Snapshot() => processes.Running.Select((process, index) =>
        new ActiveConnection(
            L4Protocol.Tcp,
            new IPEndPoint(IPAddress.Parse("192.168.1.10"), 50_000 + (process.Process.Pid % 1000)),
            new IPEndPoint(IPAddress.Parse("203.0.113.10"), 443 + (index % 3)),
            ConnectionState.Established,
            process.Process.Pid)).ToArray();
}
