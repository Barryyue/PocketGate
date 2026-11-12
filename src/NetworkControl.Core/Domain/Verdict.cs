namespace NetworkControl.Core.Domain;

/// <summary>判決依據。</summary>
public enum VerdictReason : byte
{
    /// <summary>命中規則。</summary>
    Rule = 0,

    /// <summary>由全局模式決定（白名單、黑名單、全部阻斷、停用過濾）。</summary>
    Mode = 1,

    /// <summary>用戶在彈窗中做出決定。</summary>
    Prompt = 2,

    /// <summary>彈窗超時，按默認動作處理。</summary>
    PromptTimeout = 3,

    /// <summary>內置必要流量（回環、DHCP、自身）。</summary>
    Builtin = 4,

    /// <summary>沒有可以顯示彈窗的用戶會話。</summary>
    NoUserSession = 5,

    /// <summary>勿擾模式中，按默認動作處理。</summary>
    DoNotDisturb = 6,

    /// <summary>異常兜底（隊列滿、待決數量超限、內部錯誤）。</summary>
    FailSafe = 7,

    /// <summary>服務正在停止，待決連接被取消。</summary>
    Cancelled = 8,
}

/// <summary>判決結果。</summary>
public readonly record struct Verdict(VerdictAction Action, VerdictReason Reason, Guid? RuleId = null)
{
    public static Verdict Allow(VerdictReason reason, Guid? ruleId = null) => new(VerdictAction.Allow, reason, ruleId);

    public static Verdict Deny(VerdictReason reason, Guid? ruleId = null) => new(VerdictAction.Deny, reason, ruleId);
}
