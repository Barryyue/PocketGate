using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using Microsoft.AspNetCore.Authorization;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Profiles;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Service.Security;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

[Authorize(Policy = IpcPolicies.Viewer)]
internal sealed class ProfilesGrpcService(
    ProfileService profiles,
    RuleService rules,
    RuntimeStateService state,
    ServiceStatusBuilder statusBuilder) : Proto.Profiles.ProfilesBase
{
    public override Task<Proto.ListProfilesResponse> List(Empty request, ServerCallContext context)
    {
        var response = new Proto.ListProfilesResponse();
        response.Profiles.AddRange(profiles.All.Select(ToInfo));
        return Task.FromResult(response);
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.ProfileInfo> Create(Proto.CreateProfileRequest request, ServerCallContext context)
    {
        var copyFrom = request.HasCopyRulesFrom
            ? ProtoMapper.ParseGuid(request.CopyRulesFrom, "copy_rules_from")
            : (Guid?)null;
        var profile = await ExecuteAsync(() =>
            profiles.CreateAsync(request.Name, request.Mode.ToDomain(), copyFrom, context.CancellationToken));
        return ToInfo(profile);
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.ProfileInfo> Update(Proto.ProfileInfo request, ServerCallContext context)
    {
        var id = ProtoMapper.ParseGuid(request.Id, "id");
        var profile = await ExecuteAsync(() => profiles.UpdateAsync(
            new Profile
            {
                Id = id,
                Name = request.Name,
                Mode = request.Mode.ToDomain(),
                SortOrder = request.SortOrder,
            },
            context.CancellationToken));
        return ToInfo(profile);
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Empty> Delete(Proto.DeleteProfileRequest request, ServerCallContext context)
    {
        var id = ProtoMapper.ParseGuid(request.Id, "id");
        await ExecuteAsync(() => profiles.DeleteAsync(id, context.CancellationToken));
        return new Empty();
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.ServiceStatus> Activate(Proto.ActivateProfileRequest request, ServerCallContext context)
    {
        var id = ProtoMapper.ParseGuid(request.Id, "id");
        await ExecuteAsync(() => profiles.ActivateAsync(id, ProfileSwitchReason.User, context.CancellationToken));
        return statusBuilder.Build(context.GetHttpContext().User);
    }

    private Proto.ProfileInfo ToInfo(Profile profile) => new()
    {
        Id = profile.Id.ToString(),
        Name = profile.Name,
        Mode = profile.Mode.ToProto(),
        SortOrder = profile.SortOrder,
        RuleCount = rules.All.Count(r => r.ProfileId == profile.Id),
        IsDefault = profile.Id == Profile.DefaultId,
        IsActive = profile.Id == state.ActiveProfileId,
    };

    private static async Task<T> ExecuteAsync<T>(Func<Task<T>> action)
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

    private static async Task ExecuteAsync(Func<Task> action)
    {
        try
        {
            await action();
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
}
