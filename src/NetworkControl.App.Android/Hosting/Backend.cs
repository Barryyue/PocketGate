using System.Runtime.CompilerServices;
using global::Android.Content;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Platform;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Profiles;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Core.Traffic;
using NetworkControl.Engine.Android;
using NetworkControl.Engine.Vpn;
using NetworkControl.Engine.Vpn.Backup;
using NetworkControl.Engine.Vpn.Forwarding;
using NetworkControl.Engine.Vpn.Platform;
using NetworkControl.Engine.Vpn.Routing;
using NetworkControl.Engine.Vpn.Update;
using NetworkControl.Storage.Serialization;

namespace NetworkControl.Android.Hosting;

/// <summary>保護狀態（界面顯示用）。</summary>
public enum ProtectionStatus : byte
{
    Off = 0,
    NeedsConsent = 1,
    Starting = 2,
    On = 3,
    Revoked = 4,
    Failed = 5,
}

/// <summary>用戶在應用列表中為一個應用設定的決策。</summary>
public enum AppDecision : byte
{
    /// <summary>沒有應用專屬規則：按全局模式（或全局規則）處理。</summary>
    Unset = 0,
    Allow = 1,
    Deny = 2,
}

/// <summary>應用列表中的一行。</summary>
/// <param name="App">應用。</param>
/// <param name="Decision">應用專屬規則的決策。</param>
/// <param name="Effective">當前實際的分流結果。</param>
/// <param name="Reason">分流依據。</param>
/// <param name="ExpiresAt">應用專屬規則是限時規則時的到期時間。</param>
/// <param name="IsTemporary">應用專屬規則不是永久規則（限時、直到重啟）。</param>
/// <param name="IsTampered">有規則因簽名不一致被跳過。</param>
public sealed record AppEntry(
    InstalledApp App,
    AppDecision Decision,
    AppRoutingDecision Effective,
    AppRoutingReason Reason,
    DateTimeOffset? ExpiresAt,
    bool IsTemporary,
    bool IsTampered);

/// <summary>規則導入預覽 / 結果（docs/m3-tasks.md M3-06）。</summary>
/// <param name="Total">文件中有效規則數（已排除過期規則）。</param>
/// <param name="Ineffective">其中在 Android 上不生效的規則數（桌面應用路徑或帶連線條件）。</param>
public sealed record RuleImportPreview(int Total, int Added, int Updated, int Removed, int Unchanged, int Ineffective);

/// <summary>一段時間內的流量：總計和按包名的用量（FR-STA-01）。</summary>
public sealed record TrafficUsage(long BytesIn, long BytesOut, IReadOnlyDictionary<string, (long In, long Out)> ByPackage)
{
    public static TrafficUsage Empty { get; } = new(0, 0, new Dictionary<string, (long, long)>());
}

/// <summary>
/// 界面訪問核心的門面（docs/android.md §7.1）。所有方法都可以在任意線程調用；<see cref="Changed"/> 可能在後台線程觸發。
/// </summary>
public interface IBackend
{
    event EventHandler? Changed;

    ProtectionStatus Protection { get; }

    string? ProtectionError { get; }

    FilterMode Mode { get; }

    int PendingPromptCount { get; }

    /// <summary>在 Android 上不生效的規則數（帶域名、IP、端口等條件）。</summary>
    int SkippedRuleCount { get; }

    UserSettings Settings { get; }

    VpnEngineCounters Counters { get; }

    /// <summary>用戶授權 VPN 的系統對話框；已授權時返回 null。</summary>
    Intent? CreateVpnConsentIntent();

    void SetProtection(bool enabled);

    Task SetModeAsync(FilterMode mode, CancellationToken cancellationToken);

    Task SetBlockAllAsync(bool blockAll, CancellationToken cancellationToken);

    IReadOnlyList<AppEntry> GetApps();

    Task SetAppDecisionAsync(string packageName, AppDecision decision, CancellationToken cancellationToken);

    Task<IReadOnlyList<LogEntry>> GetBlockedAsync(int limit, CancellationToken cancellationToken);

    Task<IReadOnlyList<LogEntry>> GetAllowedAsync(int limit, CancellationToken cancellationToken);

    Task<IReadOnlyList<LogEntry>> GetRecentTargetsAsync(string packageName, int limit, CancellationToken cancellationToken);

    Task AllowDomainAsync(string domain, CancellationToken cancellationToken);

    Task<long> CountBlockedSinceAsync(DateTimeOffset since, CancellationToken cancellationToken);

    IReadOnlyList<PromptSnapshot> GetPendingPrompts();

    IAsyncEnumerable<PromptEvent> SubscribePromptsAsync(CancellationToken cancellationToken);

    Task AnswerPromptAsync(Guid promptId, VerdictAction action, RuleLifetime lifetime, TimeSpan? duration, CancellationToken cancellationToken);

    /// <summary>用戶正在看這個詢問：推遲超時（不超過詢問的最長存活時間），避免內容在用戶點擊前被下一個詢問替換。</summary>
    void HoldPrompt(Guid promptId);

    Task UpdateSettingsAsync(Func<UserSettings, UserSettings> update, CancellationToken cancellationToken);

    /// <summary>是否已授予「使用情況存取權」（讀取系統流量統計需要）。</summary>
    bool HasTrafficAccess { get; }

    /// <summary>打開系統的「使用情況存取權」設定頁。</summary>
    Intent CreateTrafficAccessIntent();

    /// <summary>[from, to) 的流量。先從系統採集一次最新數據。</summary>
    Task<TrafficUsage> GetTrafficAsync(DateTimeOffset from, DateTimeOffset to, CancellationToken cancellationToken);

    IReadOnlyList<Profile> Profiles { get; }

    Profile ActiveProfile { get; }

    Task ActivateProfileAsync(Guid id, CancellationToken cancellationToken);

    Task<Profile> CreateProfileAsync(string name, FilterMode mode, AutoActivateCondition? autoActivate, CancellationToken cancellationToken);

    Task<Profile> UpdateProfileAsync(Guid id, string name, FilterMode mode, AutoActivateCondition? autoActivate, CancellationToken cancellationToken);

    /// <summary>目前的主要網路；用於設定頁的「使用目前的 Wi-Fi」。</summary>
    NetworkContext CurrentNetwork { get; }

    Task DeleteProfileAsync(Guid id, CancellationToken cancellationToken);

    int GetRuleCount(Guid profileId);

    QuietPeriodSummary? LastQuietPeriod { get; }

    void DismissQuietPeriod();

    /// <summary>語言切換後刷新通知渠道名稱、前台通知和已顯示的詢問通知。</summary>
    void RefreshNotifications();

    /// <summary>當前生效規則的快照（診斷統計用；可能包含其他配置文件與已過期的規則）。</summary>
    IReadOnlyList<Rule> Rules { get; }

    /// <summary>導出全部可導出規則（netcontrol-rules JSON，與桌面版互通）。</summary>
    Task<string> ExportRulesAsync(CancellationToken cancellationToken);

    /// <summary>解析導入文件並計算差異；不修改任何規則。拋出 <see cref="FormatException"/> 表示文件無效。</summary>
    RuleImportPreview PreviewImport(string json, RuleImportMode mode);

    /// <summary>按 <see cref="RuleImportMode"/> 執行導入：先新增、再更新，最後刪除。</summary>
    Task<RuleImportPreview> ApplyImportAsync(string json, RuleImportMode mode, CancellationToken cancellationToken);

    UiPreferences Preferences { get; }

    AppUpdateManager UpdateManager { get; }

    PermissionHealthSnapshot CheckPermissionHealth();

    Task DeleteRuleAsync(Guid ruleId, CancellationToken cancellationToken);

    Task ToggleRuleEnabledAsync(Guid ruleId, CancellationToken cancellationToken);

    Task ClearExpiredRulesAsync(CancellationToken cancellationToken);

    Task<string> UploadWebDavBackupAsync(CancellationToken cancellationToken);

    Task<string> DownloadWebDavBackupAsync(CancellationToken cancellationToken);

    string SaveLocalBackupToDownloads();

    string ReadLocalBackupFromDownloads();

    Task<Socks5DiagnosticResult> DiagnoseProxyAsync(CancellationToken cancellationToken);

    IReadOnlyList<ActiveForwardingConnection> GetActiveConnections();

    bool CutActiveConnection(string key);

    void UpdateSpeedIsland(string text);

    void RestartTunnelIfActive();

    void SwitchToDirectForwarding();

    string? GetPrivateDnsHost();

    bool IsFocusActive { get; }

    DateTimeOffset? FocusEndsAt { get; }

    Task StartFocusAsync(int minutes, IReadOnlyList<string> allowedPackages, CancellationToken cancellationToken);

    void ExtendFocus(int minutes);

    Task StopFocusAsync(CancellationToken cancellationToken);

    Task SaveFocusAsProfileAsync(CancellationToken cancellationToken);

    string? GetAppSignerSha256(string packageName);

    void TrustPublisher(string sha256);

    void RemoveTrustedPublisher(string sha256);

    void ClearTrustedPublishers();
}

internal sealed class Backend : IBackend
{
    private readonly VpnBlackholeEngine _engine;
    private readonly AndroidVpnTunnel _tunnel;
    private readonly IProtectionSwitch _protection;
    private readonly INetworkMonitor _network;
    private readonly IInstalledAppCatalog _catalog;
    private readonly RuntimeStateService _state;
    private readonly SettingsService _settings;
    private readonly ProfileService _profiles;
    private readonly QuietPeriodTracker _quietPeriods;
    private readonly RuleEngine _rules;
    private readonly RuleService _ruleService;
    private readonly PromptBroker _prompts;
    private readonly PromptNotifier _notifier;
    private readonly IActivityQuery _activity;
    private readonly TimeProvider _time;
    private readonly Context _context;
    private readonly IUidTrafficSource _trafficSource;
    private readonly UidTrafficCollector _trafficCollector;
    private readonly TrafficAggregator _trafficAggregator;
    private readonly ITrafficRepository _traffic;
    private readonly IAppRepository _apps;
    private readonly UiPreferences _preferences;
    private readonly AppUpdateManager _updateManager;
    private readonly SpeedIslandService _speedIsland;
    private readonly System.Collections.Concurrent.ConcurrentDictionary<long, string?> _packagesByAppId = new();
    private readonly string _selfPackage;
    private DateTimeOffset? _focusEndsAt;
    private FilterMode? _focusPreviousMode;
    private IReadOnlyList<string> _focusAllowedPackages = [];
    private readonly List<Guid> _focusRuleIds = [];
    private Timer? _focusTimer;

    public Backend(
        Context context,
        VpnBlackholeEngine engine,
        AndroidVpnTunnel tunnel,
        IProtectionSwitch protection,
        INetworkMonitor network,
        IInstalledAppCatalog catalog,
        RuntimeStateService state,
        SettingsService settings,
        ProfileService profiles,
        QuietPeriodTracker quietPeriods,
        RuleEngine rules,
        RuleService ruleService,
        PromptBroker prompts,
        PromptNotifier notifier,
        IActivityQuery activity,
        TimeProvider time,
        IUidTrafficSource trafficSource,
        UidTrafficCollector trafficCollector,
        TrafficAggregator trafficAggregator,
        ITrafficRepository traffic,
        IAppRepository apps,
        UiPreferences preferences,
        AppUpdateManager updateManager,
        SpeedIslandService speedIsland)
    {
        _context = context;
        _trafficSource = trafficSource;
        _trafficCollector = trafficCollector;
        _trafficAggregator = trafficAggregator;
        _traffic = traffic;
        _apps = apps;
        _preferences = preferences;
        _updateManager = updateManager;
        _speedIsland = speedIsland;
        _engine = engine;
        _tunnel = tunnel;
        _tunnel.ForwardingOptionsProvider = () => new ForwardingTunnelOptions(
            _preferences.ForwardingEnabled,
            _preferences.ForwardingUpstream != "socks5",
            _preferences.Socks5Host,
            _preferences.Socks5Port,
            string.IsNullOrWhiteSpace(_preferences.Socks5Username) ? null : _preferences.Socks5Username,
            string.IsNullOrEmpty(_preferences.Socks5Password) ? null : _preferences.Socks5Password,
            Path.Combine(_context.FilesDir!.AbsolutePath, "hev"));
        _protection = protection;
        _network = network;
        _catalog = catalog;
        _state = state;
        _settings = settings;
        _profiles = profiles;
        _quietPeriods = quietPeriods;
        _rules = rules;
        _ruleService = ruleService;
        _prompts = prompts;
        _notifier = notifier;
        _activity = activity;
        _time = time;
        _selfPackage = context.PackageName ?? "";

        _engine.StatusChanged += Raise;
        _protection.Changed += Raise;
        _state.Changed += Raise;
        _settings.Changed += Raise;
        _profiles.Changed += Raise;
        notifier.PromptsChanged += Raise;
    }

    public event EventHandler? Changed;

    public ProtectionStatus Protection
    {
        get
        {
            var tunnel = _engine.TunnelState;
            if (!_protection.IsEnabled)
            {
                return tunnel == VpnTunnelState.Revoked ? ProtectionStatus.Revoked : ProtectionStatus.Off;
            }

            return tunnel switch
            {
                VpnTunnelState.Active => ProtectionStatus.On,
                VpnTunnelState.Failed => ProtectionStatus.Failed,
                VpnTunnelState.NotPrepared => ProtectionStatus.NeedsConsent,
                _ when !_tunnel.IsPrepared => ProtectionStatus.NeedsConsent,
                _ => ProtectionStatus.Starting,
            };
        }
    }

    public string? ProtectionError => _engine.TunnelError;

    public FilterMode Mode => _state.Mode;

    public int PendingPromptCount => _prompts.GetPending().Count;

    public int SkippedRuleCount => _engine.CurrentPlan.SkippedRules.Count;

    public UserSettings Settings => _settings.Current;

    public VpnEngineCounters Counters => _engine.Counters;

    public IReadOnlyList<Profile> Profiles => _profiles.All;

    public IReadOnlyList<Rule> Rules => [.. _rules.Current.Rules];

    public Task<string> ExportRulesAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromResult(RuleExportSerializer.Export(_ruleService.All, PathPlaceholderMapper.None));
    }

    public RuleImportPreview PreviewImport(string json, RuleImportMode mode) => CreateImportPlan(json, mode).Preview;

    public async Task<RuleImportPreview> ApplyImportAsync(string json, RuleImportMode mode, CancellationToken cancellationToken)
    {
        var (plan, preview) = CreateImportPlan(json, mode);
        foreach (var rule in plan.ToAdd)
        {
            await _ruleService.AddAsync(rule, cancellationToken);
        }

        foreach (var rule in plan.ToUpdate)
        {
            await _ruleService.UpdateAsync(rule, cancellationToken);
        }

        if (plan.ToRemove.Count > 0)
        {
            await _ruleService.RemoveAsync(plan.ToRemove.Select(r => r.Id).ToArray(), RuleEndReason.Replaced, cancellationToken);
        }

        return preview;
    }

    public Profile ActiveProfile => _profiles.Active;

    public Task ActivateProfileAsync(Guid id, CancellationToken cancellationToken) =>
        _profiles.ActivateAsync(id, ProfileSwitchReason.User, cancellationToken);

    public NetworkContext CurrentNetwork => _network.Current;

    public async Task<Profile> CreateProfileAsync(string name, FilterMode mode, AutoActivateCondition? autoActivate, CancellationToken cancellationToken)
    {
        var profile = await _profiles.CreateAsync(name, mode, copyRulesFrom: null, cancellationToken);
        if (autoActivate is { IsEmpty: false })
        {
            profile = await _profiles.UpdateAsync(profile with { AutoActivate = autoActivate }, cancellationToken);
        }

        return profile;
    }

    public Task<Profile> UpdateProfileAsync(Guid id, string name, FilterMode mode, AutoActivateCondition? autoActivate, CancellationToken cancellationToken)
    {
        var existing = _profiles.Find(id) ?? throw new KeyNotFoundException($"配置文件 {id} 不存在。");
        return _profiles.UpdateAsync(existing with { Name = name, Mode = mode, AutoActivate = autoActivate }, cancellationToken);
    }

    public Task DeleteProfileAsync(Guid id, CancellationToken cancellationToken) =>
        _profiles.DeleteAsync(id, cancellationToken);

    public int GetRuleCount(Guid profileId) => _ruleService.All.Count(r => r.ProfileId == profileId);

    public QuietPeriodSummary? LastQuietPeriod => _quietPeriods.LastSummary;

    public void DismissQuietPeriod() => _quietPeriods.Dismiss();

    public void RefreshNotifications()
    {
        NotificationChannels.Ensure(_context);
        _tunnel.RefreshNotification();
        _notifier.RefreshPending();
    }

    public Intent? CreateVpnConsentIntent() => _tunnel.CreatePrepareIntent();

    public void SetProtection(bool enabled)
    {
        _protection.SetEnabled(enabled);
        // 授權剛完成時開關可能沒有變化（之前已打開），仍然需要重新規劃
        _engine.RequestReplan();
    }

    public Task SetModeAsync(FilterMode mode, CancellationToken cancellationToken) => _state.SetModeAsync(mode, cancellationToken);

    public Task SetBlockAllAsync(bool blockAll, CancellationToken cancellationToken) => blockAll
        ? _state.SetModeAsync(FilterMode.BlockAll, cancellationToken)
        : _state.RestoreFromBlockAllAsync(cancellationToken);

    public IReadOnlyList<AppEntry> GetApps()
    {
        var plan = _engine.CurrentPlan;
        var rules = _rules.Current.Rules;
        var result = new List<AppEntry>();
        foreach (var app in _catalog.GetApps())
        {
            if (app.PackageName == _selfPackage)
            {
                continue;
            }

            var own = FindOwnRule(rules, app.PackageName);
            var routing = plan.Find(app.Uid);
            result.Add(new AppEntry(
                app,
                own is null ? AppDecision.Unset : own.Action == VerdictAction.Allow ? AppDecision.Allow : AppDecision.Deny,
                routing?.Decision ?? AppRoutingDecision.BlockAndAsk,
                routing?.Reason ?? AppRoutingReason.Mode,
                own?.Lifetime == RuleLifetime.Timed ? own.ExpiresAt : null,
                own is { IsPersistent: false } or { Lifetime: RuleLifetime.Timed },
                routing?.TamperedRuleId is not null));
        }

        return result;
    }

    public async Task SetAppDecisionAsync(string packageName, AppDecision decision, CancellationToken cancellationToken)
    {
        var path = PackageAppPath.ForPackage(packageName);
        var existing = _ruleService.All
            .Where(r => r.Source is RuleSource.User or RuleSource.Prompt && IsOwnRule(r, path))
            .Select(r => r.Id)
            .ToArray();
        if (existing.Length > 0)
        {
            await _ruleService.RemoveAsync(existing, RuleEndReason.Replaced, cancellationToken);
        }

        if (decision == AppDecision.Unset || _catalog.Find(packageName) is not { } app)
        {
            return;
        }

        var now = _time.GetUtcNow();
        await _ruleService.AddAsync(new Rule
        {
            Id = Guid.CreateVersion7(now),
            App = AppMatcherFactory.Create(VpnRoutingPlanner.ToIdentity(app)),
            Conditions = new RuleConditions { Direction = Direction.Outbound },
            Action = decision == AppDecision.Allow ? VerdictAction.Allow : VerdictAction.Deny,
            Lifetime = RuleLifetime.Permanent,
            Source = RuleSource.User,
            CreatedAt = now,
            UpdatedAt = now,
        }, cancellationToken);
    }

    public Task<IReadOnlyList<LogEntry>> GetBlockedAsync(int limit, CancellationToken cancellationToken) =>
        _activity.QueryAsync(new LogQuery { Action = VerdictAction.Deny, Limit = limit }, cancellationToken);

    public Task<IReadOnlyList<LogEntry>> GetAllowedAsync(int limit, CancellationToken cancellationToken) =>
        _activity.QueryAsync(new LogQuery { Action = VerdictAction.Allow, Limit = limit }, cancellationToken);

    public async Task<IReadOnlyList<LogEntry>> GetRecentTargetsAsync(string packageName, int limit, CancellationToken cancellationToken)
    {
        var all = await _activity.QueryAsync(new LogQuery { Limit = 500 }, cancellationToken);
        return all.Where(e => PackageAppPath.TryGetPackage(e.AppPath, out var pkg) && string.Equals(pkg, packageName, StringComparison.Ordinal))
            .Take(limit)
            .ToList();
    }

    public async Task AllowDomainAsync(string domain, CancellationToken cancellationToken)
    {
        var normalized = NetworkControl.Engine.Vpn.Blocklists.DomainHash.NormalizeDomain(domain);
        if (normalized is null) return;
        var now = _time.GetUtcNow();
        await _ruleService.AddAsync(new Rule
        {
            Id = Guid.CreateVersion7(now),
            App = new AppMatcher { PathPattern = "*" },
            Conditions = new RuleConditions { Direction = Direction.Outbound, Remote = new DomainTarget(normalized) },
            Action = VerdictAction.Allow,
            Lifetime = RuleLifetime.Permanent,
            Source = RuleSource.User,
            CreatedAt = now,
            UpdatedAt = now,
        }, cancellationToken);
    }

    public async Task<long> CountBlockedSinceAsync(DateTimeOffset since, CancellationToken cancellationToken)
    {
        var entries = await _activity.QueryAsync(new LogQuery { Action = VerdictAction.Deny, From = since, Limit = 5000 }, cancellationToken);
        return entries.Sum(e => (long)e.Count);
    }

    public IReadOnlyList<PromptSnapshot> GetPendingPrompts() => _prompts.GetPending();

    public async IAsyncEnumerable<PromptEvent> SubscribePromptsAsync([EnumeratorCancellation] CancellationToken cancellationToken)
    {
        await foreach (var evt in _prompts.SubscribeAsync(new PromptSubscriber(UserId: null, IsAdmin: true), cancellationToken))
        {
            yield return evt;
        }
    }

    public Task AnswerPromptAsync(Guid promptId, VerdictAction action, RuleLifetime lifetime, TimeSpan? duration, CancellationToken cancellationToken) =>
        _prompts.AnswerAsync(promptId, new PromptDecision
        {
            Action = action,
            Lifetime = lifetime,
            Duration = duration,
            Scope = PromptScope.AnyConnection,
        }, cancellationToken);

    public void HoldPrompt(Guid promptId) => _prompts.PauseTimer(promptId);

    public bool HasTrafficAccess => _trafficSource.IsAvailable;

    public Intent CreateTrafficAccessIntent() => AndroidUidTrafficSource.CreateSettingsIntent(_context);

    public async Task<TrafficUsage> GetTrafficAsync(DateTimeOffset from, DateTimeOffset to, CancellationToken cancellationToken)
    {
        if (!_trafficSource.IsAvailable)
        {
            return TrafficUsage.Empty;
        }

        // 系統統計按小時級分桶更新：打開頁面時採集一次並立即寫入，界面看到的是最新值
        await Task.Run(_trafficCollector.CollectNow, cancellationToken);
        await _trafficAggregator.FlushAsync(cancellationToken);

        var byApp = await _traffic.ByAppAsync(from, to, cancellationToken);
        var byPackage = new Dictionary<string, (long In, long Out)>();
        long totalIn = 0, totalOut = 0;
        foreach (var (appId, usage) in byApp)
        {
            totalIn += usage.BytesIn;
            totalOut += usage.BytesOut;
            if (appId < 0)
            {
                continue;
            }

            if (!_packagesByAppId.TryGetValue(appId, out var package))
            {
                var identity = await _apps.GetAsync(appId, cancellationToken);
                package = PackageAppPath.TryGetPackage(identity?.Path, out var name) ? name : null;
                _packagesByAppId[appId] = package;
            }

            if (package is not null)
            {
                var current = byPackage.GetValueOrDefault(package);
                byPackage[package] = (current.In + usage.BytesIn, current.Out + usage.BytesOut);
            }
        }

        return new TrafficUsage(totalIn, totalOut, byPackage);
    }

    public Task UpdateSettingsAsync(Func<UserSettings, UserSettings> update, CancellationToken cancellationToken) =>
        _settings.UpdateAsync(update, cancellationToken);

    public UiPreferences Preferences => _preferences;

    public AppUpdateManager UpdateManager => _updateManager;

    public PermissionHealthSnapshot CheckPermissionHealth()
    {
        var appListOk = _catalog.GetApps().Count > 0;
        return AndroidPermissionHelper.Check(_context, HasTrafficAccess, appListOk);
    }

    public async Task DeleteRuleAsync(Guid ruleId, CancellationToken cancellationToken)
    {
        await _ruleService.RemoveAsync([ruleId], RuleEndReason.Deleted, cancellationToken).ConfigureAwait(false);
        Raise(this, EventArgs.Empty);
    }

    public async Task ToggleRuleEnabledAsync(Guid ruleId, CancellationToken cancellationToken)
    {
        var rule = _ruleService.All.FirstOrDefault(r => r.Id == ruleId);
        if (rule is null) return;
        await _ruleService.UpdateAsync(rule with { Enabled = !rule.Enabled, UpdatedAt = _time.GetUtcNow() }, cancellationToken).ConfigureAwait(false);
        Raise(this, EventArgs.Empty);
    }

    public async Task ClearExpiredRulesAsync(CancellationToken cancellationToken)
    {
        var now = _time.GetUtcNow();
        var expired = _ruleService.All
            .Where(r => r.Lifetime == RuleLifetime.Timed && r.ExpiresAt <= now)
            .Select(r => r.Id)
            .ToArray();
        if (expired.Length > 0)
        {
            await _ruleService.RemoveAsync(expired, RuleEndReason.Expired, cancellationToken).ConfigureAwait(false);
            Raise(this, EventArgs.Empty);
        }
    }

    public async Task<string> UploadWebDavBackupAsync(CancellationToken cancellationToken)
    {
        if (_preferences.OfflineMode) throw new InvalidOperationException("offline_mode");
        var json = await ExportRulesAsync(cancellationToken).ConfigureAwait(false);
        var url = await WebDavBackupClient.UploadAsync(
            _preferences.WebDavUrl,
            _preferences.WebDavUsername,
            _preferences.WebDavPassword,
            _preferences.WebDavPassphrase,
            json,
            cancellationToken).ConfigureAwait(false);
        _preferences.WebDavLastSyncAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        return url;
    }

    public async Task<string> DownloadWebDavBackupAsync(CancellationToken cancellationToken)
    {
        if (_preferences.OfflineMode) throw new InvalidOperationException("offline_mode");
        return await WebDavBackupClient.DownloadAsync(
            _preferences.WebDavUrl,
            _preferences.WebDavUsername,
            _preferences.WebDavPassword,
            _preferences.WebDavPassphrase,
            cancellationToken).ConfigureAwait(false);
    }

    public string SaveLocalBackupToDownloads() =>
        LocalRulesBackup.SaveToDownloads(_context, RuleExportSerializer.Export(_ruleService.All, PathPlaceholderMapper.None));

    public string ReadLocalBackupFromDownloads() =>
        LocalRulesBackup.ReadLatestFromDownloads(_context);

    public Task<Socks5DiagnosticResult> DiagnoseProxyAsync(CancellationToken cancellationToken) =>
        Socks5Probe.DiagnoseAsync(_preferences.Socks5Host, _preferences.Socks5Port, _preferences.Socks5Username, _preferences.Socks5Password, cancellationToken);

    public IReadOnlyList<ActiveForwardingConnection> GetActiveConnections() =>
        _tunnel.ConnectionTracker.Snapshot(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

    public bool CutActiveConnection(string key) =>
        _tunnel.ConnectionTracker.RequestCut(key);

    public void UpdateSpeedIsland(string text) =>
        _speedIsland.Update(_preferences.SpeedIslandEnabled, text);

    public void RestartTunnelIfActive()
    {
        if (!_protection.IsEnabled) return;
        _ = Task.Run(async () =>
        {
            SetProtection(false);
            await Task.Delay(150).ConfigureAwait(false);
            SetProtection(true);
        });
    }

    public void SwitchToDirectForwarding()
    {
        _preferences.ForwardingUpstream = "direct";
        RestartTunnelIfActive();
        Raise(this, EventArgs.Empty);
    }

    public string? GetPrivateDnsHost()
    {
        try
        {
            var cm = _context.GetSystemService(Context.ConnectivityService) as global::Android.Net.ConnectivityManager;
            var lp = cm?.GetLinkProperties(cm.ActiveNetwork);
            if (lp?.IsPrivateDnsActive == true)
            {
                return lp.PrivateDnsServerName ?? "strict";
            }
        }
        catch
        {
        }

        return null;
    }

    public bool IsFocusActive => _focusEndsAt is { } end && end > _time.GetUtcNow();

    public DateTimeOffset? FocusEndsAt => IsFocusActive ? _focusEndsAt : null;

    public async Task StartFocusAsync(int minutes, IReadOnlyList<string> allowedPackages, CancellationToken cancellationToken)
    {
        await StopFocusAsync(cancellationToken);
        var now = _time.GetUtcNow();
        var end = now.AddMinutes(Math.Clamp(minutes, 5, 1440));
        _focusPreviousMode = _state.Mode;
        _focusAllowedPackages = [.. allowedPackages];
        _focusEndsAt = end;
        foreach (var pkg in allowedPackages)
        {
            if (_catalog.Find(pkg) is not { } app) continue;
            var ruleId = Guid.CreateVersion7(now);
            _focusRuleIds.Add(ruleId);
            await _ruleService.AddAsync(new Rule
            {
                Id = ruleId,
                App = AppMatcherFactory.Create(VpnRoutingPlanner.ToIdentity(app)),
                Conditions = new RuleConditions { Direction = Direction.Outbound },
                Action = VerdictAction.Allow,
                Lifetime = RuleLifetime.Timed,
                ExpiresAt = end,
                Source = RuleSource.User,
                CreatedAt = now,
                UpdatedAt = now,
            }, cancellationToken);
        }

        await _state.SetModeAsync(FilterMode.Blacklist, cancellationToken);
        _focusTimer?.Dispose();
        _focusTimer = new Timer(_ => _ = StopFocusAsync(CancellationToken.None), null, TimeSpan.FromMinutes(Math.Clamp(minutes, 5, 1440)), Timeout.InfiniteTimeSpan);
        Raise(this, EventArgs.Empty);
    }

    public void ExtendFocus(int minutes)
    {
        if (!IsFocusActive || _focusEndsAt is null) return;
        _focusEndsAt = _focusEndsAt.Value.AddMinutes(minutes);
        var remaining = _focusEndsAt.Value - _time.GetUtcNow();
        if (remaining > TimeSpan.Zero)
        {
            _focusTimer?.Change(remaining, Timeout.InfiniteTimeSpan);
        }

        Raise(this, EventArgs.Empty);
    }

    public async Task StopFocusAsync(CancellationToken cancellationToken)
    {
        _focusTimer?.Dispose();
        _focusTimer = null;
        if (_focusEndsAt is null) return;
        _focusEndsAt = null;
        if (_focusRuleIds.Count > 0)
        {
            await _ruleService.RemoveAsync(_focusRuleIds.ToArray(), RuleEndReason.Expired, cancellationToken);
        }

        _focusRuleIds.Clear();
        _focusAllowedPackages = [];
        if (_focusPreviousMode is { } prev)
        {
            _focusPreviousMode = null;
            await _state.SetModeAsync(prev, cancellationToken);
        }

        Raise(this, EventArgs.Empty);
    }

    public async Task SaveFocusAsProfileAsync(CancellationToken cancellationToken)
    {
        if (_focusAllowedPackages.Count == 0) return;
        var profile = await _profiles.CreateAsync($"Focus {DateTime.Now:HH:mm}", FilterMode.Blacklist, null, cancellationToken);
        var now = _time.GetUtcNow();
        foreach (var pkg in _focusAllowedPackages)
        {
            if (_catalog.Find(pkg) is not { } app) continue;
            await _ruleService.AddAsync(new Rule
            {
                Id = Guid.CreateVersion7(now),
                ProfileId = profile.Id,
                App = AppMatcherFactory.Create(VpnRoutingPlanner.ToIdentity(app)),
                Conditions = new RuleConditions { Direction = Direction.Outbound },
                Action = VerdictAction.Allow,
                Lifetime = RuleLifetime.Permanent,
                Source = RuleSource.User,
                CreatedAt = now,
                UpdatedAt = now,
            }, cancellationToken);
        }
    }

    public string? GetAppSignerSha256(string packageName)
    {
        try
        {
            var pm = _context.PackageManager;
            if (pm is null) return null;
#pragma warning disable CA1422
            var info = pm.GetPackageInfo(packageName, global::Android.Content.PM.PackageInfoFlags.SigningCertificates | global::Android.Content.PM.PackageInfoFlags.Signatures);
            var sig = info?.SigningInfo?.GetApkContentsSigners()?.FirstOrDefault() ?? info?.Signatures?.FirstOrDefault();
#pragma warning restore CA1422
            if (sig?.ToByteArray() is not { } sigBytes) return null;
            var hash = System.Security.Cryptography.SHA256.HashData(sigBytes);
            return Convert.ToHexString(hash)[..16];
        }
        catch
        {
            return null;
        }
    }

    public void TrustPublisher(string sha256)
    {
        if (string.IsNullOrWhiteSpace(sha256)) return;
        var list = _preferences.TrustedPublishers.ToHashSet(StringComparer.OrdinalIgnoreCase);
        list.Add(sha256);
        _preferences.TrustedPublishers = list;
        Raise(this, EventArgs.Empty);
    }

    public void RemoveTrustedPublisher(string sha256)
    {
        var list = _preferences.TrustedPublishers.Where(x => !string.Equals(x, sha256, StringComparison.OrdinalIgnoreCase)).ToList();
        _preferences.TrustedPublishers = list;
        Raise(this, EventArgs.Empty);
    }

    public void ClearTrustedPublishers()
    {
        _preferences.TrustedPublishers = [];
        Raise(this, EventArgs.Empty);
    }

    /// <summary>
    /// 解析導入文件並計算差異。與 RulesGrpcService 一致：丟棄已過期的限時規則，
    /// 目標機器上不存在的配置文件降級為共享規則（ProfileId = null）。
    /// </summary>
    private (RuleImportPlan Plan, RuleImportPreview Preview) CreateImportPlan(string json, RuleImportMode mode)
    {
        var imported = RuleExportSerializer.Import(json, PathPlaceholderMapper.None);
        var now = _time.GetUtcNow();
        var knownProfiles = _profiles.All.Select(p => p.Id).ToHashSet();
        var current = imported
            .Where(r => r.Lifetime != RuleLifetime.Timed || r.ExpiresAt > now)
            .Select(r => r.ProfileId is { } profileId && !knownProfiles.Contains(profileId)
                ? r with { ProfileId = null }
                : r)
            .ToArray();
        var plan = RuleImportPlanner.Plan(_ruleService.All, current, mode);
        var preview = new RuleImportPreview(
            current.Length,
            plan.ToAdd.Count,
            plan.ToUpdate.Count,
            plan.ToRemove.Count,
            plan.Unchanged,
            AndroidRuleCompatibility.CountIneffective(current));
        return (plan, preview);
    }

    /// <summary>按優先級找到該應用的第一條專屬、可在 Android 上執行的規則。</summary>
    private static Rule? FindOwnRule(IReadOnlyList<Rule> rules, string packageName)
    {
        var path = PackageAppPath.ForPackage(packageName);
        return rules.FirstOrDefault(r => IsOwnRule(r, path));
    }

    private static bool IsOwnRule(Rule rule, string path) =>
        rule.App is { HasWildcard: false } matcher
        && string.Equals(matcher.PathPattern, path, StringComparison.Ordinal)
        && VpnRoutingPlanner.IsEnforceable(rule);

    private void Raise(object? sender, EventArgs e) => Changed?.Invoke(this, EventArgs.Empty);
}
