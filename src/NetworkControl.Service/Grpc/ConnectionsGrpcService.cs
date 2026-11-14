using System.Net;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using Microsoft.AspNetCore.Authorization;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;
using NetworkControl.Service.Security;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

[Authorize(Policy = IpcPolicies.Viewer)]
internal sealed class ConnectionsGrpcService(
    IEnumerable<IConnectionTable> tables,
    IServiceProvider services,
    DnsMapper dns,
    TimeProvider time) : Proto.Connections.ConnectionsBase
{
    private const int MaxConnections = 5000;

    public override async Task<Proto.ListConnectionsResponse> List(Proto.ListConnectionsRequest request, ServerCallContext context)
    {
        var table = tables.FirstOrDefault();
        if (table is null)
        {
            return new Proto.ListConnectionsResponse { Supported = false };
        }

        var processes = services.GetService<IProcessInfoProvider>();
        var identities = services.GetService<IAppIdentityResolver>();
        var resolved = await ConnectionResolver.ResolveAsync(
            table.Snapshot(),
            processes,
            identities,
            dns,
            request.IncludeListening,
            request.IncludeLoopback,
            MaxConnections,
            time.GetUtcNow(),
            context.CancellationToken);

        var response = new Proto.ListConnectionsResponse { Supported = true };
        foreach (var entry in resolved)
        {
            var connection = entry.Connection;
            var live = new Proto.LiveConnection
            {
                Protocol = ToProto(connection.Protocol),
                Local = connection.Local.ToProto(),
                State = ToProto(connection.State),
                Pid = connection.ProcessId,
            };
            if (connection.Remote is { } remote)
            {
                live.Remote = remote.ToProto();
            }

            if (entry.App is { } app)
            {
                live.AppId = app.AppId;
                live.AppName = app.Name;
                live.AppPath = app.Path;
            }
            else if (entry.Process is { } process)
            {
                live.AppPath = process.ExecutablePath;
            }

            if (entry.Hostname is { } hostname)
            {
                live.Hostname = hostname;
            }

            response.Connections.Add(live);
        }

        return response;
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.KillConnectionResponse> Kill(Proto.KillConnectionRequest request, ServerCallContext context)
    {
        var local = request.Local;
        var remote = request.Remote;
        if (!System.Enum.IsDefined(request.Protocol) || request.Protocol != Proto.Protocol.Tcp
            || local is null || remote is null)
        {
            return new Proto.KillConnectionResponse { Message = "只支持有效的 IPv4 TCP 连接。" };
        }

        if (!IPAddress.TryParse(local.Address, out var localAddress)
            || !IPAddress.TryParse(remote.Address, out var remoteAddress)
            || local.Port > ushort.MaxValue || remote.Port > ushort.MaxValue)
        {
            return new Proto.KillConnectionResponse { Message = "只支持有效的 IPv4 TCP 连接。" };
        }

        var engine = services.GetService<INetworkEngine>();
        if (engine is null || !engine.Capabilities.HasFlag(EngineCapabilities.KillConnection))
        {
            return new Proto.KillConnectionResponse { Message = "当前引擎不支持断开连接。" };
        }

        if (localAddress.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork
            || remoteAddress.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
        {
            return new Proto.KillConnectionResponse { Message = "当前仅支持 IPv4 TCP 连接。" };
        }

        var killed = await engine.KillConnectionAsync(
            new ConnectionKey(
                L4Protocol.Tcp,
                new IPEndPoint(localAddress, (int)local.Port),
                new IPEndPoint(remoteAddress, (int)remote.Port)),
            context.CancellationToken);
        return new Proto.KillConnectionResponse
        {
            Killed = killed,
            Message = killed ? "连接已断开。" : "连接不存在或断开失败。",
        };
    }

    private static Proto.Protocol ToProto(L4Protocol protocol) => protocol switch
    {
        L4Protocol.Tcp => Proto.Protocol.Tcp,
        L4Protocol.Udp => Proto.Protocol.Udp,
        L4Protocol.Icmp => Proto.Protocol.Icmp,
        L4Protocol.IcmpV6 => Proto.Protocol.Icmpv6,
        _ => Proto.Protocol.Other,
    };

    private static Proto.ConnectionStateKind ToProto(ConnectionState state) => state switch
    {
        ConnectionState.Listen => Proto.ConnectionStateKind.Listen,
        ConnectionState.Established => Proto.ConnectionStateKind.Established,
        ConnectionState.SynSent => Proto.ConnectionStateKind.SynSent,
        ConnectionState.SynReceived => Proto.ConnectionStateKind.SynReceived,
        ConnectionState.Closing => Proto.ConnectionStateKind.Closing,
        ConnectionState.TimeWait => Proto.ConnectionStateKind.TimeWait,
        ConnectionState.Udp => Proto.ConnectionStateKind.Udp,
        _ => Proto.ConnectionStateKind.Closed,
    };
}
