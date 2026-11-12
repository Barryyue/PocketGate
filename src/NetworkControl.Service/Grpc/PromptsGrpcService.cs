using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using Microsoft.AspNetCore.Authorization;
using NetworkControl.Core.Prompts;
using NetworkControl.Service.Security;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

[Authorize(Policy = IpcPolicies.Viewer)]
internal sealed class PromptsGrpcService(PromptBroker broker) : Proto.Prompts.PromptsBase
{
    public override async Task Subscribe(Empty request, IServerStreamWriter<Proto.PromptEvent> responseStream, ServerCallContext context)
    {
        using var streaming = StreamingCancellation.Link(context);
        var user = context.GetHttpContext().User;
        var subscriber = new PromptSubscriber(user.GetPeerUserId(), user.IsPeerAdmin());

        try
        {
            await foreach (var evt in broker.SubscribeAsync(subscriber, streaming.Token))
            {
                await responseStream.WriteAsync(evt.ToProto(), streaming.Token);
            }
        }
        catch (OperationCanceledException) when (streaming.IsCancellationRequested)
        {
            // 客戶端斷開
        }
    }

    public override async Task<Proto.AnswerResponse> Answer(Proto.AnswerRequest request, ServerCallContext context)
    {
        var id = ProtoMapper.ParseGuid(request.PromptId, "prompt_id");
        EnsureCanAnswer(id, context);

        var result = await broker.AnswerAsync(id, request.ToDomain(), context.CancellationToken);
        var response = new Proto.AnswerResponse
        {
            Found = result.Found,
            ResolvedFlows = result.ResolvedFlows,
            RemainingFlows = result.RemainingFlows,
        };
        if (result.CreatedRuleId is { } ruleId)
        {
            response.CreatedRuleId = ruleId.ToString();
        }

        return response;
    }

    public override Task<Proto.PauseTimerResponse> PauseTimer(Proto.PromptRef request, ServerCallContext context)
    {
        var id = ProtoMapper.ParseGuid(request.PromptId, "prompt_id");
        EnsureCanAnswer(id, context);
        return Task.FromResult(new Proto.PauseTimerResponse { Paused = broker.PauseTimer(id) });
    }

    /// <summary>非管理員只能回應發給自己的彈窗（FR-PRM-13）。</summary>
    private void EnsureCanAnswer(Guid promptId, ServerCallContext context)
    {
        var user = context.GetHttpContext().User;
        if (user.IsPeerAdmin())
        {
            return;
        }

        var prompt = broker.GetPending().FirstOrDefault(p => p.Id == promptId);
        if (prompt?.TargetUserId is { } target && !string.Equals(target, user.GetPeerUserId(), StringComparison.OrdinalIgnoreCase))
        {
            throw new RpcException(new Status(StatusCode.PermissionDenied, "只能回應發給自己的彈窗。"));
        }
    }
}
