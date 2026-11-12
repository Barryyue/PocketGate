namespace NetworkControl.Core.Domain;

/// <summary>彈窗決策的作用範圍。</summary>
public enum PromptScope : byte
{
    /// <summary>此應用的任意連接（同方向）。</summary>
    AnyConnection = 0,

    /// <summary>此域名。</summary>
    Domain = 1,

    /// <summary>此 IP。</summary>
    Address = 2,

    /// <summary>此 IP + 端口 + 協議。</summary>
    AddressAndPort = 3,

    /// <summary>此端口 + 協議。</summary>
    Port = 4,
}

/// <summary>用戶在彈窗中做出的決策。</summary>
public sealed record PromptDecision
{
    public required VerdictAction Action { get; init; }

    public PromptScope Scope { get; init; } = PromptScope.AnyConnection;

    public RuleLifetime Lifetime { get; init; } = RuleLifetime.Permanent;

    /// <summary><see cref="RuleLifetime.Timed"/> 的時長。</summary>
    public TimeSpan? Duration { get; init; }

    /// <summary><see cref="PromptScope.Domain"/> 時選中的域名；為空則使用首選候選域名。</summary>
    public string? SelectedDomain { get; init; }

    /// <summary>為 false 時只作用於彈窗當前顯示的那條連接。</summary>
    public bool ApplyToAllFlows { get; init; } = true;
}
