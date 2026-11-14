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
    IEnumerable<INetworkMonitor> monitors,
    ServiceStatusBuilder statusBuilder) : Proto.Profiles.ProfilesBase
{
    public override Task<Proto.ListProfilesResponse> List(Empty request, ServerCallContext context)
    {
        var counts = rules.All
            .Where(r => r.ProfileId is not null)
            .GroupBy(r => r.ProfileId!.Value)
            .ToDictionary(g => g.Key, g => g.Count());
        var response = new Proto.ListProfilesResponse();
        response.Profiles.AddRange(profiles.All.Select(p => ToInfo(p, counts)));
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
        if (AutoCondition(request.AutoKind, request.HasAutoKind, request.AutoSsids, request.AutoNetworkIds) is { } auto)
        {
            profile = await ExecuteAsync(() => profiles.UpdateAsync(profile with { AutoActivate = auto }, context.CancellationToken));
        }

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
                AutoActivate = AutoCondition(request.AutoKind, request.HasAutoKind, request.AutoSsids, request.AutoNetworkIds),
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

    public override Task<Proto.NetworkInfo> GetNetwork(Empty request, ServerCallContext context)
    {
        var network = monitors.FirstOrDefault()?.Current;
        var info = new Proto.NetworkInfo
        {
            Kind = network is null ? Proto.NetworkKindValue.None : (Proto.NetworkKindValue)(int)network.Kind,
            DisplayName = network?.DisplayName ?? "",
        };
        if (network?.Ssid is { } ssid)
        {
            info.Ssid = ssid;
        }

        if (network?.NetworkId is { } id)
        {
            info.NetworkId = id;
        }

        return Task.FromResult(info);
    }

    private Proto.ProfileInfo ToInfo(Profile profile, IReadOnlyDictionary<Guid, int>? counts = null)
    {
        var info = new Proto.ProfileInfo
        {
            Id = profile.Id.ToString(),
            Name = profile.Name,
            Mode = profile.Mode.ToProto(),
            SortOrder = profile.SortOrder,
            RuleCount = counts?.GetValueOrDefault(profile.Id) ?? rules.All.Count(r => r.ProfileId == profile.Id),
            IsDefault = profile.Id == Profile.DefaultId,
            IsActive = profile.Id == state.ActiveProfileId,
        };

        if (profile.AutoActivate is { } auto)
        {
            if (auto.Kind is { } kind)
            {
                info.AutoKind = (Proto.NetworkKindValue)(int)kind;
            }

            info.AutoSsids.AddRange(auto.Ssids);
            info.AutoNetworkIds.AddRange(auto.NetworkIds);
        }

        return info;
    }

    private static AutoActivateCondition? AutoCondition(
        NetworkControl.Ipc.V1.NetworkKindValue kind,
        bool hasKind,
        IEnumerable<string> ssids,
        IEnumerable<string> networkIds)
    {
        var ssidArray = ssids.ToArray();
        var idArray = networkIds.ToArray();
        if (!hasKind && ssidArray.Length == 0 && idArray.Length == 0)
        {
            return null;
        }

        return new AutoActivateCondition(hasKind ? (NetworkKind)(int)kind : null, ssidArray, idArray);
    }

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
