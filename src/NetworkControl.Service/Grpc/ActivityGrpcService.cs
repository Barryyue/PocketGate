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

    public override async Task<Proto.SummaryReply> GetSummary(Proto.SummaryQuery request, ServerCallContext context)
    {
        var to = request.To?.ToDateTimeOffset() ?? DateTimeOffset.UtcNow;
        var from = request.From?.ToDateTimeOffset() ?? to.AddDays(-1);
        if (from >= to || to - from > TimeSpan.FromDays(400))
        {
            throw new RpcException(new Status(StatusCode.InvalidArgument, "統計時間範圍無效。"));
        }

        var summary = await query.SummarizeAsync(
            new ActivitySummaryQuery
            {
                From = from,
                To = to,
                BucketSize = request.Bucket?.ToTimeSpan() is { } bucket && bucket >= TimeSpan.FromMinutes(1) ? bucket : TimeSpan.FromHours(1),
                TopCount = request.TopCount > 0 ? Math.Min(request.TopCount, 100) : 8,
            },
            context.CancellationToken);

        var reply = new Proto.SummaryReply
        {
            Allowed = summary.Allowed,
            Blocked = summary.Blocked,
            AppCount = summary.AppCount,
            DestinationCount = summary.DestinationCount,
        };
        reply.Timeline.AddRange(summary.Timeline.Select(b => new Proto.SummaryBucket { Start = Timestamp.FromDateTimeOffset(b.Start), Allowed = b.Allowed, Blocked = b.Blocked }));
        reply.TopApps.AddRange(summary.TopApps.Select(ToProto));
        reply.TopDestinations.AddRange(summary.TopDestinations.Select(ToProto));
        return reply;
    }

    private static Proto.SummaryItem ToProto(ActivityTopItem item)
    {
        var proto = new Proto.SummaryItem { Key = item.Key, Allowed = item.Allowed, Blocked = item.Blocked };
        if (item.Label is { } label)
        {
            proto.Label = label;
        }

        if (item.AppId is { } appId)
        {
            proto.AppId = appId;
        }

        if (item.Path is { } path)
        {
            proto.Path = path;
        }

        return proto;
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
