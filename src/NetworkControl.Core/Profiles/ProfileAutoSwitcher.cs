using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Profiles;

/// <summary>自動切換配置文件後的通知。</summary>
public sealed record ProfileAutoSwitchEvent(Profile Profile, NetworkContext Network);

/// <summary>
/// 網絡變化後去抖 3 秒，按配置文件順序找第一個符合條件的配置文件並切換。
/// 用戶在同一個網絡上手動切換過後，該網絡不再自動切換，直到網絡變化。
/// </summary>
public sealed partial class ProfileAutoSwitcher : BackgroundService
{
    private static readonly TimeSpan Debounce = TimeSpan.FromSeconds(3);
    private static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(1);

    private readonly INetworkMonitor? _monitor;
    private readonly ProfileService _profiles;
    private readonly TimeProvider _time;
    private readonly ILogger<ProfileAutoSwitcher> _logger;
    private readonly Lock _gate = new();
    private NetworkContext _current;
    private DateTimeOffset _observedAt;
    private bool _pending;
    private string? _manualFingerprint;

    public ProfileAutoSwitcher(
        IEnumerable<INetworkMonitor> monitors,
        ProfileService profiles,
        TimeProvider time,
        ILogger<ProfileAutoSwitcher> logger)
    {
        _monitor = monitors.FirstOrDefault();
        _profiles = profiles;
        _time = time;
        _logger = logger;
        _current = _monitor?.Current ?? NetworkContext.None;
    }

    public event EventHandler<ProfileAutoSwitchEvent>? Switched;

    public override Task StartAsync(CancellationToken cancellationToken)
    {
        if (_monitor is not null)
        {
            _monitor.Changed += OnNetworkChanged;
            _profiles.Activated += OnProfileActivated;
            _current = _monitor.Current;
            _observedAt = _time.GetUtcNow();
            _pending = true;
        }

        return base.StartAsync(cancellationToken);
    }

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        if (_monitor is not null)
        {
            _monitor.Changed -= OnNetworkChanged;
            _profiles.Activated -= OnProfileActivated;
        }

        await base.StopAsync(cancellationToken);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (_monitor is null)
        {
            return;
        }

        using var timer = new PeriodicTimer(PollInterval, _time);
        try
        {
            while (await timer.WaitForNextTickAsync(stoppingToken))
            {
                await EvaluateAsync(stoppingToken);
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // 停止
        }
    }

    private void OnNetworkChanged(object? sender, EventArgs e)
    {
        if (_monitor is null)
        {
            return;
        }

        lock (_gate)
        {
            var next = _monitor.Current;
            if (next.Fingerprint == _current.Fingerprint)
            {
                return;
            }

            _current = next;
            _manualFingerprint = null;
            _observedAt = _time.GetUtcNow();
            _pending = true;
        }
    }

    private void OnProfileActivated(object? sender, ProfileActivatedEventArgs e)
    {
        if (e.Reason != ProfileSwitchReason.User)
        {
            return;
        }

        lock (_gate)
        {
            _manualFingerprint = _current.Fingerprint;
        }
    }

    private async Task EvaluateAsync(CancellationToken cancellationToken)
    {
        NetworkContext network;
        lock (_gate)
        {
            if (!_pending || _time.GetUtcNow() - _observedAt < Debounce)
            {
                return;
            }

            _pending = false;
            network = _current;
            if (_manualFingerprint == network.Fingerprint)
            {
                if (_logger.IsEnabled(LogLevel.Debug))
                {
                    LogManualOverride(_logger, network.Fingerprint);
                }
                return;
            }
        }

        var target = _profiles.All
            .OrderBy(p => p.SortOrder)
            .FirstOrDefault(p => p.AutoActivate?.Matches(network) == true);
        if (target is null || target.Id == _profiles.Active.Id)
        {
            return;
        }

        try
        {
            await _profiles.ActivateAsync(target.Id, ProfileSwitchReason.Network, cancellationToken);
            if (_logger.IsEnabled(LogLevel.Information))
            {
                var profileName = target.Name;
                var networkName = network.DisplayName ?? network.Kind.ToString();
                LogSwitched(_logger, profileName, networkName);
            }

            Switched?.Invoke(this, new ProfileAutoSwitchEvent(target, network));
        }
        catch (Exception ex) when (ex is ArgumentException or KeyNotFoundException)
        {
            LogFailed(_logger, target.Id, ex);
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "網絡變化後自動切換到配置文件「{Profile}」（{Network}）")]
    private static partial void LogSwitched(ILogger logger, string profile, string network);

    [LoggerMessage(Level = LogLevel.Debug, Message = "網絡 {Fingerprint} 由用戶手動選擇過配置文件，跳過自動切換")]
    private static partial void LogManualOverride(ILogger logger, string fingerprint);

    [LoggerMessage(Level = LogLevel.Warning, Message = "自動切換配置文件 {ProfileId} 失敗")]
    private static partial void LogFailed(ILogger logger, Guid profileId, Exception exception);
}
