using global::Android.App;
using global::Android.Content;
using global::Android.Net;
using Microsoft.Extensions.Logging;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Android;

/// <summary>【宿主實現】VPN 前台通知：文案和點擊行為由應用提供（需要本地化）。</summary>
public interface IVpnForegroundNotifier
{
    int NotificationId { get; }

    Notification Build(Context context);

    /// <summary>系統 VPN 設定中「配置」按鈕打開的界面。</summary>
    PendingIntent? CreateConfigureIntent(Context context);
}

/// <summary>
/// <see cref="IVpnTunnel"/> 的 Android 實現：按需啟動 <see cref="NcVpnService"/>，建立 / 重建虛擬網卡並啟動讀線程。
/// 進程內單例（服務由系統創建，經 <see cref="Current"/> 找到它）。
/// </summary>
public sealed partial class AndroidVpnTunnel : IVpnTunnel
{
    private static readonly TimeSpan ServiceStartTimeout = TimeSpan.FromSeconds(10);

    private readonly Context _context;
    private readonly IVpnForegroundNotifier _notifier;
    private readonly ILogger<AndroidVpnTunnel> _logger;
    private readonly Lock _gate = new();
    private TaskCompletionSource<NcVpnService> _serviceReady = NewServiceSource();
    private NcVpnService? _service;
    private TunReader? _reader;
    private volatile VpnTunnelState _state;
    private volatile string? _lastError;

    public AndroidVpnTunnel(Context context, IVpnForegroundNotifier notifier, ILogger<AndroidVpnTunnel> logger)
    {
        _context = context.ApplicationContext ?? context;
        _notifier = notifier;
        _logger = logger;
        Current = this;
    }

    /// <summary>系統直接啟動了 VPN 服務（始終開啟的 VPN）：宿主應確保核心已啟動並打開保護。</summary>
    public event EventHandler? SystemStartRequested;

    public event EventHandler? StateChanged;

    internal static AndroidVpnTunnel? Current { get; private set; }

    public VpnTunnelState State => _state;

    public string? LastError => _lastError;

    public bool IsPrepared => VpnService.Prepare(_context) is null;

    /// <summary>用戶授權 VPN 的系統對話框；已授權時返回 null。需要由 Activity 以 StartActivityForResult 打開。</summary>
    public Intent? CreatePrepareIntent() => VpnService.Prepare(_context);

    public async Task ApplyAsync(VpnTunnelConfig config, IPacketSink sink, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(config);
        ArgumentNullException.ThrowIfNull(sink);
        if (State != VpnTunnelState.Active)
        {
            SetState(VpnTunnelState.Establishing);
        }

        NcVpnService service;
        try
        {
            service = await EnsureServiceAsync(cancellationToken);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            Fail("啟動 VPN 服務失敗：" + ex.Message);
            throw;
        }

        var descriptor = service.Establish(config, _notifier.CreateConfigureIntent(_context), (package, ex) => LogPackageSkipped(_logger, ex, package));
        if (descriptor is null)
        {
            // 授權被收回（例如用戶在系統設定中「忘記」了本 VPN）
            _lastError = "尚未獲得建立 VPN 的授權。";
            SetState(VpnTunnelState.NotPrepared);
            return;
        }

        var reader = new TunReader(descriptor, sink, config.Mtu, OnReaderFailed);
        TunReader? previous;
        lock (_gate)
        {
            previous = _reader;
            _reader = reader;
        }

        // 新網卡建立後再關閉舊的：切換期間不斷網
        previous?.Dispose();

        try
        {
            service.EnsureForeground(_notifier.NotificationId, _notifier.Build(_context));
        }
        catch (Exception ex) when (ex is Java.Lang.SecurityException or Java.Lang.IllegalStateException)
        {
            // 前台通知失敗不影響攔截（VPN 服務由系統綁定，進程不會被輕易回收）
            LogForegroundFailed(_logger, ex);
        }

        _lastError = null;
        LogEstablished(_logger, config.BypassPackages.Count);
        SetState(VpnTunnelState.Active);
    }

    public Task CloseAsync(CancellationToken cancellationToken)
    {
        TunReader? reader;
        NcVpnService? service;
        lock (_gate)
        {
            reader = _reader;
            _reader = null;
            service = _service;
        }

        reader?.Dispose();
        service?.Shutdown();
        if (State is not (VpnTunnelState.Revoked or VpnTunnelState.NotPrepared))
        {
            SetState(VpnTunnelState.Closed);
        }

        return Task.CompletedTask;
    }

    /// <summary>刷新前台通知（例如語言變化後）。</summary>
    public void RefreshNotification()
    {
        NcVpnService? service;
        lock (_gate)
        {
            service = _service;
        }

        service?.UpdateForeground(_notifier.NotificationId, _notifier.Build(_context));
    }

    internal void OnServiceCreated(NcVpnService service)
    {
        TaskCompletionSource<NcVpnService> ready;
        lock (_gate)
        {
            _service = service;
            ready = _serviceReady;
        }

        ready.TrySetResult(service);
    }

    internal void OnServiceDestroyed(NcVpnService service)
    {
        TunReader? reader = null;
        lock (_gate)
        {
            if (!ReferenceEquals(_service, service))
            {
                return;
            }

            _service = null;
            _serviceReady = NewServiceSource();
            reader = _reader;
            _reader = null;
        }

        reader?.Dispose();
        if (State == VpnTunnelState.Active || State == VpnTunnelState.Establishing)
        {
            SetState(VpnTunnelState.Closed);
        }
    }

    internal void OnRevoked(NcVpnService service)
    {
        TunReader? reader;
        lock (_gate)
        {
            reader = _reader;
            _reader = null;
        }

        reader?.Dispose();
        _lastError = "VPN 已被系統撤銷（可能啟用了其他 VPN）。";
        LogRevoked(_logger);
        SetState(VpnTunnelState.Revoked);
    }

    internal void OnSystemStart()
    {
        LogSystemStart(_logger);
        SystemStartRequested?.Invoke(this, EventArgs.Empty);
    }

    private async Task<NcVpnService> EnsureServiceAsync(CancellationToken cancellationToken)
    {
        Task<NcVpnService> ready;
        lock (_gate)
        {
            if (_service is { } existing)
            {
                return existing;
            }

            ready = _serviceReady.Task;
        }

        var intent = new Intent(_context, typeof(NcVpnService)).SetAction(NcVpnService.ActionStart);
        _context.StartForegroundService(intent);
        return await ready.WaitAsync(ServiceStartTimeout, cancellationToken);
    }

    private void OnReaderFailed(Exception exception)
    {
        LogReaderFailed(_logger, exception);
        Fail("讀取虛擬網卡失敗：" + exception.Message);
    }

    private void Fail(string message)
    {
        _lastError = message;
        SetState(VpnTunnelState.Failed);
    }

    private void SetState(VpnTunnelState state)
    {
        _state = state;
        StateChanged?.Invoke(this, EventArgs.Empty);
    }

    private static TaskCompletionSource<NcVpnService> NewServiceSource() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    [LoggerMessage(Level = LogLevel.Information, Message = "虛擬網卡已建立，{Count} 個包被排除在 VPN 之外")]
    private static partial void LogEstablished(ILogger logger, int count);

    [LoggerMessage(Level = LogLevel.Warning, Message = "VPN 已被系統撤銷")]
    private static partial void LogRevoked(ILogger logger);

    [LoggerMessage(Level = LogLevel.Information, Message = "VPN 服務由系統啟動（始終開啟的 VPN）")]
    private static partial void LogSystemStart(ILogger logger);

    [LoggerMessage(Level = LogLevel.Warning, Message = "應用 {Package} 已不存在，未加入排除名單")]
    private static partial void LogPackageSkipped(ILogger logger, Exception exception, string package);

    [LoggerMessage(Level = LogLevel.Warning, Message = "VPN 服務無法成為前台服務")]
    private static partial void LogForegroundFailed(ILogger logger, Exception exception);

    [LoggerMessage(Level = LogLevel.Error, Message = "讀取虛擬網卡失敗")]
    private static partial void LogReaderFailed(ILogger logger, Exception exception);
}
