using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using Grpc.Net.Client;
using NetworkControl.App.Infrastructure;
using NetworkControl.Ipc;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Services;

public enum ConnectionState
{
    Connecting,
    Connected,
    Disconnected,
}

public enum ConnectionFailureKind
{
    /// <summary>端點不存在：服務沒有運行。</summary>
    NotRunning,

    /// <summary>端點存在但拒絕訪問。</summary>
    AccessDenied,

    /// <summary>服務的 IPC 協議版本與界面不一致。</summary>
    ProtocolMismatch,

    Other,
}

/// <summary>最近一次連接失敗的原因，用於在界面上給出具體的提示。</summary>
public sealed record ConnectionFailure(ConnectionFailureKind Kind, IpcEndpoint Endpoint, string? Detail = null, uint ServiceProtocol = 0);

/// <summary>一次成功連接上的 gRPC 客戶端集合。連接斷開後整體作廢，重連時重新創建。</summary>
public sealed class IpcClients(GrpcChannel channel)
{
    public GrpcChannel Channel { get; } = channel;

    public Proto.Control.ControlClient Control { get; } = new(channel);

    public Proto.Prompts.PromptsClient Prompts { get; } = new(channel);

    public Proto.Rules.RulesClient Rules { get; } = new(channel);

    public Proto.ActivityLog.ActivityLogClient Activity { get; } = new(channel);

    public Proto.Apps.AppsClient Apps { get; } = new(channel);

    public Proto.Connections.ConnectionsClient Connections { get; } = new(channel);

    public Proto.Simulator.SimulatorClient Simulator { get; } = new(channel);

    public Proto.Diagnostics.DiagnosticsClient Diagnostics { get; } = new(channel);
}

/// <summary>
/// 與核心服務的連接：自動發現端點、斷線指數退避重連、訂閱狀態推送，並記錄失敗原因。
/// 屬性變更總是在 UI 線程上觸發，可以直接綁定。
/// </summary>
public sealed partial class ServiceConnection : ObservableObject, IAsyncDisposable
{
    private static readonly TimeSpan MaxBackoff = TimeSpan.FromSeconds(5);

    private readonly CancellationTokenSource _cts = new();
    private readonly SemaphoreSlim _wake = new(0, 1);
    private Task? _loop;

    /// <summary>每次成功連接後觸發（UI 線程）。訂閱者應在此重新建立自己的流式訂閱。</summary>
    public event EventHandler<IpcClients>? Connected;

    [ObservableProperty]
    public partial ConnectionState State { get; private set; } = ConnectionState.Connecting;

    [ObservableProperty]
    public partial Proto.ServiceStatus? Status { get; private set; }

    [ObservableProperty]
    public partial ConnectionFailure? LastFailure { get; private set; }

    /// <summary>當前可用的客戶端；未連接時為 null。</summary>
    public IpcClients? Clients { get; private set; }

    public bool IsConnected => State == ConnectionState.Connected && Clients is not null;

    public void Start() => _loop ??= Task.Run(() => RunAsync(_cts.Token));

    /// <summary>跳過退避等待，立即重試（例如剛剛啟動了服務）。</summary>
    public void RetryNow()
    {
        if (_wake.CurrentCount == 0)
        {
            _wake.Release();
        }
    }

    /// <summary>取得已連接的客戶端；未連接時拋出異常，由頁面統一提示。</summary>
    public IpcClients RequireClients() => Clients ?? throw new InvalidOperationException(LastFailure?.Detail ?? "Not connected");

    public async ValueTask DisposeAsync()
    {
        await _cts.CancelAsync();
        if (_loop is not null)
        {
            try
            {
                await _loop;
            }
            catch (OperationCanceledException)
            {
                // 正常停止
            }
        }

        _cts.Dispose();
        _wake.Dispose();
    }

    partial void OnStateChanged(ConnectionState value) => OnPropertyChanged(nameof(IsConnected));

    private async Task RunAsync(CancellationToken ct)
    {
        var backoff = TimeSpan.FromMilliseconds(500);
        while (!ct.IsCancellationRequested)
        {
            ConnectionFailure? failure = null;
            foreach (var endpoint in IpcEndpoint.ResolveForClient())
            {
                if (!endpoint.MayBeAvailable)
                {
                    failure ??= new ConnectionFailure(ConnectionFailureKind.NotRunning, endpoint);
                    continue;
                }

                using var channel = IpcChannelFactory.Create(endpoint);
                var clients = new IpcClients(channel);
                try
                {
                    var status = await clients.Control.GetStatusAsync(new Empty(), deadline: DateTime.UtcNow.AddSeconds(3), cancellationToken: ct);
                    if (status.ProtocolVersion != IpcEndpoint.ProtocolVersion)
                    {
                        failure = new ConnectionFailure(ConnectionFailureKind.ProtocolMismatch, endpoint, ServiceProtocol: status.ProtocolVersion);
                        continue;
                    }

                    backoff = TimeSpan.FromMilliseconds(500);
                    AppLog.Info($"Connected to service {status.ServiceVersion} at {endpoint} (engine {status.Engine.Name}, {status.Engine.State})");
                    Post(() =>
                    {
                        Clients = clients;
                        Status = status;
                        LastFailure = null;
                        State = ConnectionState.Connected;
                        Connected?.Invoke(this, clients);
                    });

                    // 狀態流結束（服務停止 / 連接斷開）即視為斷線
                    await WatchStatusAsync(clients, ct);
                    failure = new ConnectionFailure(ConnectionFailureKind.NotRunning, endpoint);
                }
                catch (Exception ex) when (ex is RpcException or IOException or HttpRequestException or TimeoutException or UnauthorizedAccessException)
                {
                    failure = Classify(endpoint, ex);
                }
                finally
                {
                    Post(() =>
                    {
                        if (ReferenceEquals(Clients, clients))
                        {
                            Clients = null;
                        }
                    });
                }
            }

            LogFailureIfChanged(failure);
            Post(() =>
            {
                State = ConnectionState.Disconnected;
                Status = null;
                LastFailure = failure;
            });

            // 等待退避時間，或被 RetryNow 提前喚醒
            await _wake.WaitAsync(backoff, ct);
            backoff = TimeSpan.FromTicks(Math.Min(backoff.Ticks * 2, MaxBackoff.Ticks));
        }
    }

    private async Task WatchStatusAsync(IpcClients clients, CancellationToken ct)
    {
        using var call = clients.Control.WatchStatus(new Empty(), cancellationToken: ct);
        await foreach (var status in call.ResponseStream.ReadAllAsync(ct))
        {
            Post(() => Status = status);
        }
    }

    private ConnectionFailure? _loggedFailure;

    /// <summary>斷線重試期間每次失敗都會走到這裡：只在原因變化時寫日誌，避免刷屏。</summary>
    private void LogFailureIfChanged(ConnectionFailure? failure)
    {
        if (failure is not null && failure != _loggedFailure)
        {
            AppLog.Warn($"Service unavailable: {failure.Kind} at {failure.Endpoint} {failure.Detail}");
        }

        _loggedFailure = failure;
    }

    private static ConnectionFailure Classify(IpcEndpoint endpoint, Exception ex)
    {
        for (var e = ex; e is not null; e = e.InnerException)
        {
            if (e is UnauthorizedAccessException
                || e is RpcException { StatusCode: StatusCode.PermissionDenied or StatusCode.Unauthenticated })
            {
                return new ConnectionFailure(ConnectionFailureKind.AccessDenied, endpoint, e.Message);
            }
        }

        if (!endpoint.MayBeAvailable)
        {
            return new ConnectionFailure(ConnectionFailureKind.NotRunning, endpoint);
        }

        var detail = ex is RpcException rpc ? rpc.Status.Detail : ex.Message;
        return new ConnectionFailure(ConnectionFailureKind.Other, endpoint, detail);
    }

    private static void Post(Action action) => Dispatcher.UIThread.Post(action);
}
