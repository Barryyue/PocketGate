using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Settings;

/// <summary>
/// 用戶可以在界面上修改的設置。部署相關的靜態配置（數據目錄、IPC 端點、引擎選擇）走 appsettings / Options，不放這裡。
/// </summary>
public sealed record UserSettings
{
    public static readonly UserSettings Default = new();

    /// <summary>彈窗超時時間（FR-INT-08）。</summary>
    public TimeSpan PromptTimeout { get; init; } = TimeSpan.FromSeconds(30);

    /// <summary>彈窗超時、勿擾、無用戶會話時的默認動作。</summary>
    public VerdictAction PromptDefaultAction { get; init; } = VerdictAction.Deny;

    /// <summary>彈窗倒計時可以被暫停延長的上限（從彈窗創建時算起）。</summary>
    public TimeSpan MaxPromptLifetime { get; init; } = TimeSpan.FromMinutes(5);

    /// <summary>引擎無法掛起連接時，「本次允許」轉換為臨時規則的時長。</summary>
    public TimeSpan OnceFallbackDuration { get; init; } = TimeSpan.FromSeconds(60);

    /// <summary>同時等待用戶決策的連接數上限。</summary>
    public int MaxPendingFlows { get; init; } = 1000;

    public FailPolicy FailPolicy { get; init; } = FailPolicy.FailOpen;

    /// <summary>為 true 時 ICMP 不受規則控制，直接放行（FR-INT-05）。</summary>
    public bool AllowIcmpByDefault { get; init; } = true;

    /// <summary>連接日誌保留天數。</summary>
    public int LogRetentionDays { get; init; } = 30;
}

/// <summary>運行時狀態的持久化形式。</summary>
public sealed record RuntimeStateRecord
{
    public Guid ActiveProfileId { get; init; } = Profile.DefaultId;

    public FilterMode Mode { get; init; } = FilterMode.Ask;

    /// <summary>進入「全部阻斷」之前的模式，用於一鍵恢復。</summary>
    public FilterMode? ModeBeforeBlockAll { get; init; }

    public DateTimeOffset? DoNotDisturbUntil { get; init; }
}

/// <summary>【存儲實現】設置與運行時狀態的持久化。</summary>
public interface ISettingsRepository
{
    Task<UserSettings?> LoadUserSettingsAsync(CancellationToken cancellationToken);

    Task SaveUserSettingsAsync(UserSettings settings, CancellationToken cancellationToken);

    Task<RuntimeStateRecord?> LoadRuntimeStateAsync(CancellationToken cancellationToken);

    Task SaveRuntimeStateAsync(RuntimeStateRecord state, CancellationToken cancellationToken);
}

/// <summary>持有當前用戶設置，變更即持久化並通知。</summary>
public sealed class SettingsService(ISettingsRepository repository)
{
    private UserSettings _current = UserSettings.Default;

    public event EventHandler? Changed;

    public UserSettings Current => Volatile.Read(ref _current);

    public async Task InitializeAsync(CancellationToken cancellationToken)
    {
        var loaded = await repository.LoadUserSettingsAsync(cancellationToken);
        Volatile.Write(ref _current, loaded ?? UserSettings.Default);
    }

    public async Task<UserSettings> UpdateAsync(Func<UserSettings, UserSettings> update, CancellationToken cancellationToken)
    {
        var next = Validate(update(Current));
        await repository.SaveUserSettingsAsync(next, cancellationToken);
        Volatile.Write(ref _current, next);
        Changed?.Invoke(this, EventArgs.Empty);
        return next;
    }

    private static UserSettings Validate(UserSettings s) => s with
    {
        PromptTimeout = Clamp(s.PromptTimeout, TimeSpan.FromSeconds(5), TimeSpan.FromMinutes(5)),
        MaxPromptLifetime = Clamp(s.MaxPromptLifetime, TimeSpan.FromSeconds(10), TimeSpan.FromMinutes(30)),
        OnceFallbackDuration = Clamp(s.OnceFallbackDuration, TimeSpan.FromSeconds(10), TimeSpan.FromMinutes(10)),
        MaxPendingFlows = Math.Clamp(s.MaxPendingFlows, 10, 100_000),
        LogRetentionDays = Math.Clamp(s.LogRetentionDays, 1, 3650),
    };

    private static TimeSpan Clamp(TimeSpan value, TimeSpan min, TimeSpan max) =>
        TimeSpan.FromTicks(Math.Clamp(value.Ticks, min.Ticks, max.Ticks));
}
