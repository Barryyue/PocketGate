namespace NetworkControl.Core.Domain;

/// <summary>規則時效。</summary>
public enum RuleLifetime : byte
{
    /// <summary>僅本次。在支持掛起的引擎上不產生持久規則。</summary>
    Once = 0,

    /// <summary>直到綁定的進程退出。</summary>
    UntilProcessExit = 1,

    /// <summary>到達 <see cref="Rule.ExpiresAt"/> 後失效。</summary>
    Timed = 2,

    /// <summary>直到服務重啟，只保存在內存中。</summary>
    UntilRestart = 3,

    Permanent = 4,
}

/// <summary>規則來源。</summary>
public enum RuleSource : byte
{
    User = 0,
    Prompt = 1,
    Builtin = 2,
    Subscription = 3,
    Import = 4,
}

/// <summary>規則結束（過期、刪除）的原因，用於 rule_history 審計。</summary>
public enum RuleEndReason : byte
{
    Deleted = 0,
    Expired = 1,
    ProcessExited = 2,
    Replaced = 3,
}

/// <summary>
/// 規則：「應用 + 條件 + 動作 + 時效」。規則是不可變的值對象，修改即替換。
/// </summary>
public sealed record Rule
{
    public required Guid Id { get; init; }

    /// <summary>所屬配置文件；為 null 表示共享規則，對所有配置文件生效。</summary>
    public Guid? ProfileId { get; init; }

    /// <summary>為 null 表示任意應用（全局規則）。</summary>
    public AppMatcher? App { get; init; }

    public RuleConditions Conditions { get; init; } = RuleConditions.Any;

    public required VerdictAction Action { get; init; }

    public required RuleLifetime Lifetime { get; init; }

    /// <summary><see cref="RuleLifetime.Timed"/> 規則的過期時間。</summary>
    public DateTimeOffset? ExpiresAt { get; init; }

    /// <summary><see cref="RuleLifetime.UntilProcessExit"/> 規則綁定的進程。</summary>
    public ProcessRef? BoundProcess { get; init; }

    public required RuleSource Source { get; init; }

    public bool Enabled { get; init; } = true;

    /// <summary>大於 0 表示用戶手動置頂，數值越大越優先。</summary>
    public int ManualPriority { get; init; }

    public string? Note { get; init; }

    public required DateTimeOffset CreatedAt { get; init; }

    public required DateTimeOffset UpdatedAt { get; init; }

    /// <summary>該規則是否需要寫入持久化存儲。</summary>
    public bool IsPersistent => Lifetime is not (RuleLifetime.Once or RuleLifetime.UntilRestart);

    /// <summary>規則在給定時刻是否仍處於有效期內（不考慮進程存活）。</summary>
    public bool IsLiveAt(DateTimeOffset now) => Lifetime != RuleLifetime.Timed || ExpiresAt is null || ExpiresAt > now;
}
