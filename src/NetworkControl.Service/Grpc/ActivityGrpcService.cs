using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using Microsoft.AspNetCore.Authorization;
using NetworkControl.Core.Activity;
using NetworkControl.Service.Security;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

[Authorize(Policy = IpcPolicies.Viewer)]
internal sealed class ActivityGrpcService(IActivityQuery query, LiveActivityFeed feed) : Proto.ActivityLog.ActivityLogBase
{
    public override async Task<Proto.LogPage> QueryLog(Proto.LogQuery request, ServerCallContext context)
    {
        var entries = await query.QueryAsync(request.ToDomain(), context.CancellationToken);
        var page = new Proto.LogPage();
        page.Entries.AddRange(entries.Select(e => e.ToProto()));
        return page;
    }

    public override async Task WatchConnections(Empty request, IServerStreamWriter<Proto.ConnectionEvent> responseStream, ServerCallContext context)
    {
        using var streaming = StreamingCancellation.Link(context);
        try
        {
            await foreach (var evt in feed.Events.SubscribeAsync(streaming.Token))
            {
                await responseStream.WriteAsync(evt.ToProto(), streaming.Token);
            }
        }
        catch (OperationCanceledException) when (streaming.IsCancellationRequested)
        {
            // 客戶端斷開
        }
    }
}
