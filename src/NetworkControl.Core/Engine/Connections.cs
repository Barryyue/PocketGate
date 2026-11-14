using System.Net;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Engine;

/// <summary>一條當前存在的 TCP 連線或 UDP 端點。</summary>
public sealed record ActiveConnection(
    L4Protocol Protocol,
    IPEndPoint Local,
    IPEndPoint? Remote,
    ConnectionState State,
    int ProcessId);

public enum ConnectionState : byte
{
    Listen,
    Established,
    SynSent,
    SynReceived,
    Closing,
    TimeWait,
    Closed,
    Udp,
}

/// <summary>【平台實現】讀取系統當前連線表；不支持的平台返回空列表。</summary>
public interface IConnectionTable
{
    IReadOnlyList<ActiveConnection> Snapshot();
}

/// <summary>連線表的一條記錄，已補上應用身份和域名（可能為 null）。</summary>
public sealed record ResolvedConnection(
    ActiveConnection Connection,
    ProcessSnapshot? Process,
    AppIdentity? App,
    string? Hostname);

/// <summary>把平台連線表轉成界面需要的形狀：過濾、補進程 / 應用身份、反查域名。</summary>
public static class ConnectionResolver
{
    public static bool IsLoopback(ActiveConnection connection) =>
        IPAddress.IsLoopback(connection.Local.Address)
        || (connection.Remote is { } remote && IPAddress.IsLoopback(remote.Address));

    public static async Task<IReadOnlyList<ResolvedConnection>> ResolveAsync(
        IEnumerable<ActiveConnection> connections,
        IProcessInfoProvider? processes,
        IAppIdentityResolver? identities,
        DnsMapper? dns,
        bool includeListening,
        bool includeLoopback,
        int maxConnections,
        DateTimeOffset timestamp,
        CancellationToken cancellationToken)
    {
        var result = new List<ResolvedConnection>();
        foreach (var connection in connections)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!includeListening && connection.State == ConnectionState.Listen)
            {
                continue;
            }

            if (!includeLoopback && IsLoopback(connection))
            {
                continue;
            }

            ProcessSnapshot? process = null;
            if (processes is not null && processes.TryGetProcess(connection.ProcessId, out var snapshot))
            {
                process = snapshot;
            }

            AppIdentity? app = null;
            if (process is not null && identities is not null)
            {
                var flow = new FlowInfo
                {
                    FlowId = 0,
                    Direction = Direction.Outbound,
                    Protocol = connection.Protocol,
                    Local = connection.Local,
                    Remote = connection.Remote ?? connection.Local,
                    Process = process.Process,
                    ExecutablePath = process.ExecutablePath,
                    Timestamp = timestamp,
                };
                app = await identities.ResolveAsync(flow, cancellationToken);
            }

            string? hostname = null;
            if (connection.Remote is { } remote && process is not null && dns is not null)
            {
                hostname = dns.Lookup(remote.Address, process.Process).Primary;
            }

            result.Add(new ResolvedConnection(connection, process, app, hostname));
            if (result.Count >= maxConnections)
            {
                break;
            }
        }

        return result;
    }
}
