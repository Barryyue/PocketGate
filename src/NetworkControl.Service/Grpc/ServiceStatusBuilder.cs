using System.Reflection;
using System.Security.Claims;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Profiles;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Ipc;
using NetworkControl.Service.Security;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

/// <summary>構建 IPC 的 <see cref="Proto.ServiceStatus"/>，供 Control 和 Profiles 共用。</summary>
internal sealed class ServiceStatusBuilder(
    RuntimeStateService state,
    EngineStatus engine,
    RuleEngine rules,
    PromptBroker prompts,
    ProfileService profiles,
    IEnumerable<NetworkControl.Core.Profiles.INetworkMonitor> monitors)
{
    private static readonly string ServiceVersion =
        typeof(ServiceStatusBuilder).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "0.0.0";

    public Proto.ServiceStatus Build(ClaimsPrincipal user)
    {
        var snapshot = state.Snapshot;

        var engineInfo = new Proto.EngineInfo
        {
            Name = engine.Name,
            State = (Proto.EngineState)(int)engine.State,
            HoldsConnections = engine.Capabilities.HasFlag(EngineCapabilities.PendingVerdict),
        };
        engineInfo.Capabilities.AddRange(System.Enum.GetValues<EngineCapabilities>()
            .Where(c => c != EngineCapabilities.None && engine.Capabilities.HasFlag(c))
            .Select(c => c.ToString()));
        if (engine.LastFault is { } fault)
        {
            engineInfo.FaultMessage = fault.Message;
        }

        var client = new Proto.ClientInfo { IsAdmin = user.IsPeerAdmin() };
        if (user.GetPeerProcessId() is { } pid)
        {
            client.Pid = pid;
        }

        if (user.GetPeerUserId() is { } userId)
        {
            client.UserId = userId;
        }

        return new Proto.ServiceStatus
        {
            ProtocolVersion = IpcEndpoint.ProtocolVersion,
            ServiceVersion = ServiceVersion,
            Mode = snapshot.Mode.ToProto(),
            ActiveProfileId = snapshot.ActiveProfileId.ToString(),
            ActiveProfileName = profiles.Find(snapshot.ActiveProfileId)?.Name ?? "",
            NetworkDisplayName = monitors.FirstOrDefault()?.Current.DisplayName ?? "",
            DoNotDisturbUntil = state.IsDoNotDisturb ? snapshot.DoNotDisturbUntil.ToProto() : null,
            Engine = engineInfo,
            ActiveRuleCount = rules.Current.Count,
            PendingPromptFlows = prompts.PendingFlowCount,
            Client = client,
        };
    }
}
