using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using Microsoft.AspNetCore.Authorization;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Rules;
using NetworkControl.Service.Security;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

[Authorize(Policy = IpcPolicies.Viewer)]
internal sealed class RulesGrpcService(RuleService rules, TimeProvider time) : Proto.Rules.RulesBase
{
    public override Task<Proto.ListRulesResponse> List(Proto.ListRulesRequest request, ServerCallContext context)
    {
        Guid? profile = request.HasProfileId ? ProtoMapper.ParseGuid(request.ProfileId, "profile_id") : null;
        var response = new Proto.ListRulesResponse();
        response.Rules.AddRange(rules.All
            .Where(r => profile is null || r.ProfileId is null || r.ProfileId == profile)
            .OrderByDescending(r => r.UpdatedAt)
            .Select(ToInfo));
        return Task.FromResult(response);
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.RuleInfo> Create(Proto.RuleDefinition request, ServerCallContext context)
    {
        var now = time.GetUtcNow();
        var rule = request.ToRule(Guid.CreateVersion7(now), RuleSource.User, now);
        return ToInfo(await Execute(() => rules.AddAsync(rule, context.CancellationToken)));
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.RuleInfo> Update(Proto.UpdateRuleRequest request, ServerCallContext context)
    {
        var id = ProtoMapper.ParseGuid(request.Id, "id");
        var existing = rules.Find(id) ?? throw NotFound(id);
        var rule = request.Definition.ToRule(id, existing.Source, time.GetUtcNow());
        return ToInfo(await Execute(() => rules.UpdateAsync(rule, context.CancellationToken)));
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.RuleInfo> SetEnabled(Proto.SetRuleEnabledRequest request, ServerCallContext context)
    {
        var id = ProtoMapper.ParseGuid(request.Id, "id");
        return ToInfo(await Execute(() => rules.SetEnabledAsync(id, request.Enabled, context.CancellationToken)));
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.DeleteRulesResponse> Delete(Proto.DeleteRulesRequest request, ServerCallContext context)
    {
        var ids = request.Ids.Select(i => ProtoMapper.ParseGuid(i, "ids")).ToArray();
        var deleted = await rules.RemoveAsync(ids, RuleEndReason.Deleted, context.CancellationToken);
        return new Proto.DeleteRulesResponse { Deleted = deleted };
    }

    public override async Task Watch(Empty request, IServerStreamWriter<Proto.RuleChangeEvent> responseStream, ServerCallContext context)
    {
        using var streaming = StreamingCancellation.Link(context);
        try
        {
            await foreach (var change in rules.Changes.SubscribeAsync(streaming.Token))
            {
                await responseStream.WriteAsync(new Proto.RuleChangeEvent
                {
                    Kind = (Proto.RuleChangeKind)(int)change.Kind,
                    Rule = ToInfo(change.Rule),
                }, streaming.Token);
            }
        }
        catch (OperationCanceledException) when (streaming.IsCancellationRequested)
        {
            // 客戶端斷開
        }
    }

    private Proto.RuleInfo ToInfo(Rule rule)
    {
        var (count, lastHit) = rules.GetHitStats(rule.Id);
        return rule.ToProto(count, lastHit);
    }

    private static async Task<Rule> Execute(Func<Task<Rule>> action)
    {
        try
        {
            return await action();
        }
        catch (KeyNotFoundException ex)
        {
            throw new RpcException(new Status(StatusCode.NotFound, ex.Message));
        }
        catch (ArgumentException ex)
        {
            throw ProtoMapper.Invalid(ex.Message);
        }
    }

    private static RpcException NotFound(Guid id) => new(new Status(StatusCode.NotFound, $"規則 {id} 不存在。"));
}
