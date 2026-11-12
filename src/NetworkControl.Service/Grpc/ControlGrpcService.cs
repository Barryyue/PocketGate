using System.Reflection;
using System.Threading.Channels;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using Microsoft.AspNetCore.Authorization;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Ipc;
using NetworkControl.Service.Security;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

[Authorize(Policy = IpcPolicies.Viewer)]
internal sealed class ControlGrpcService(
    RuntimeStateService state,
    SettingsService settings,
    EngineStatus engine,
    RuleEngine rules,
    PromptBroker prompts,
    IHostApplicationLifetime lifetime,
    TimeProvider time) : Proto.Control.ControlBase
{
    private static readonly string ServiceVersion =
        typeof(ControlGrpcService).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "0.0.0";

    private static readonly TimeSpan StatusRefreshInterval = TimeSpan.FromSeconds(5);

    public override Task<Proto.ServiceStatus> GetStatus(Empty request, ServerCallContext context) =>
        Task.FromResult(BuildStatus(context));

    public override async Task WatchStatus(Empty request, IServerStreamWriter<Proto.ServiceStatus> responseStream, ServerCallContext context)
    {
        using var streaming = StreamingCancellation.Link(context);
        // 任何狀態源變化時推送一次；另外定期刷新（例如待決彈窗計數）
        var signal = Channel.CreateBounded<bool>(new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropWrite });
        void OnChanged(object? sender, EventArgs e) => signal.Writer.TryWrite(true);

        state.Changed += OnChanged;
        engine.Changed += OnChanged;
        rules.RuleSetChanged += OnChanged;
        try
        {
            using var timer = new PeriodicTimer(StatusRefreshInterval, time);
            var ct = streaming.Token;
            _ = Task.Run(async () =>
            {
                while (await timer.WaitForNextTickAsync(ct))
                {
                    signal.Writer.TryWrite(true);
                }
            }, ct);

            await responseStream.WriteAsync(BuildStatus(context), ct);
            await foreach (var _ in signal.Reader.ReadAllAsync(ct))
            {
                await responseStream.WriteAsync(BuildStatus(context), ct);
            }
        }
        catch (OperationCanceledException) when (streaming.IsCancellationRequested)
        {
            // 客戶端斷開
        }
        finally
        {
            state.Changed -= OnChanged;
            engine.Changed -= OnChanged;
            rules.RuleSetChanged -= OnChanged;
        }
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.ServiceStatus> SetMode(Proto.SetModeRequest request, ServerCallContext context)
    {
        await state.SetModeAsync(request.Mode.ToDomain(), context.CancellationToken);
        return BuildStatus(context);
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.ServiceStatus> RestoreFromBlockAll(Empty request, ServerCallContext context)
    {
        await state.RestoreFromBlockAllAsync(context.CancellationToken);
        return BuildStatus(context);
    }

    public override async Task<Proto.ServiceStatus> SetDoNotDisturb(Proto.SetDoNotDisturbRequest request, ServerCallContext context)
    {
        var duration = request.Duration?.ToTimeSpan();
        DateTimeOffset? until = duration is { } d && d > TimeSpan.Zero ? time.GetUtcNow() + d : null;
        await state.SetDoNotDisturbAsync(until, context.CancellationToken);
        return BuildStatus(context);
    }

    public override Task<Proto.UserSettings> GetSettings(Empty request, ServerCallContext context) =>
        Task.FromResult(settings.Current.ToProto());

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.UserSettings> UpdateSettings(Proto.UserSettings request, ServerCallContext context)
    {
        var updated = await settings.UpdateAsync(current => request.ApplyTo(current), context.CancellationToken);
        return updated.ToProto();
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override Task<Empty> Shutdown(Empty request, ServerCallContext context)
    {
        // 先返回響應，再停止宿主：停止過程會清理引擎（WFP 動態過濾器隨會話關閉自動刪除）
        _ = Task.Run(async () =>
        {
            await Task.Delay(TimeSpan.FromMilliseconds(200), time);
            lifetime.StopApplication();
        });
        return Task.FromResult(new Empty());
    }

    private Proto.ServiceStatus BuildStatus(ServerCallContext context)
    {
        var snapshot = state.Snapshot;
        var user = context.GetHttpContext().User;

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
            DoNotDisturbUntil = state.IsDoNotDisturb ? snapshot.DoNotDisturbUntil.ToProto() : null,
            Engine = engineInfo,
            ActiveRuleCount = rules.Current.Count,
            PendingPromptFlows = prompts.PendingFlowCount,
            Client = client,
        };
    }
}
