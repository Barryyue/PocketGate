using System.Net;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using Microsoft.AspNetCore.Authorization;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Profiles;
using NetworkControl.Engine.Fake;
using NetworkControl.Service.Security;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

/// <summary>開發用模擬器，只在使用模擬引擎時映射。</summary>
[Authorize(Policy = IpcPolicies.Admin)]
internal sealed class SimulatorGrpcService(INetworkSimulator simulator, FakeNetworkMonitor networks) : Proto.Simulator.SimulatorBase
{
    public override Task<Proto.SimulatorApps> ListApps(Empty request, ServerCallContext context)
    {
        var response = new Proto.SimulatorApps();
        foreach (var app in simulator.Apps)
        {
            var msg = new Proto.SimulatorApp { Key = app.Key, DisplayName = app.DisplayName, Path = app.Path };
            if (app.SignerId is { } signer)
            {
                msg.SignerId = signer;
            }

            response.Apps.Add(msg);
        }

        return Task.FromResult(response);
    }

    public override async Task<Proto.SimulateResponse> Simulate(Proto.SimulateRequest request, ServerCallContext context)
    {
        if (request.Port is 0 or > ushort.MaxValue)
        {
            throw ProtoMapper.Invalid($"無效的端口 {request.Port}。");
        }

        IPAddress? address = null;
        if (request.HasAddress && !IPAddress.TryParse(request.Address, out address))
        {
            throw ProtoMapper.Invalid($"無效的地址 {request.Address}。");
        }

        try
        {
            var result = await simulator.SimulateAsync(new SimulatedFlowRequest
            {
                AppKey = request.AppKey,
                Hostname = request.HasHostname ? request.Hostname : null,
                Address = address,
                Port = (ushort)request.Port,
                Protocol = request.Protocol.ToDomain() ?? L4Protocol.Tcp,
                Direction = request.Direction.ToDomain(),
            }, context.CancellationToken);

            return new Proto.SimulateResponse
            {
                FlowId = result.FlowId,
                Action = result.Action.ToProto(),
                Remote = result.Remote.ToProto(),
                Pid = result.Pid,
            };
        }
        catch (KeyNotFoundException ex)
        {
            throw new RpcException(new Status(StatusCode.NotFound, ex.Message));
        }
    }

    public override Task<Proto.ExitAppResponse> ExitApp(Proto.ExitAppRequest request, ServerCallContext context) =>
        Task.FromResult(new Proto.ExitAppResponse { Exited = simulator.ExitApp(request.AppKey) });

    public override Task<Empty> SimulateNetwork(Proto.SimulateNetworkRequest request, ServerCallContext context)
    {
        networks.Set(new NetworkContext(
            (NetworkKind)(int)request.Kind,
            request.HasSsid ? request.Ssid : null,
            request.HasNetworkId ? request.NetworkId : null,
            request.HasDisplayName ? request.DisplayName : null));
        return Task.FromResult(new Empty());
    }
}
