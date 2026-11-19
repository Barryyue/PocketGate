using NetworkControl.Core.Domain;

namespace NetworkControl.Engine.Vpn.Routing;

/// <summary>一個 UID 的分流決策。</summary>
public enum AppRoutingDecision : byte
{
    /// <summary>排除在 VPN 之外：直接使用真實網絡（允許）。</summary>
    Bypass = 0,

    /// <summary>進入 VPN 被丟棄（拒絕），只記錄日誌。</summary>
    Block = 1,

    /// <summary>進入 VPN 被丟棄，並在聯網嘗試時詢問用戶（詢問模式下沒有規則的應用）。</summary>
    BlockAndAsk = 2,
}

/// <summary>決策依據。</summary>
public enum AppRoutingReason : byte
{
    /// <summary>本應用自身。</summary>
    Self = 0,

    /// <summary>系統核心 UID（小於 10000），屬於內置必要流量。</summary>
    SystemUid = 1,

    /// <summary>命中規則。</summary>
    Rule = 2,

    /// <summary>沒有規則命中，按全局模式處理。</summary>
    Mode = 3,
}

/// <summary>一個 UID 的分流結果。</summary>
/// <param name="Uid">UID。</param>
/// <param name="PackageName">代表這個 UID 的包名（共享 UID 時取排序後的第一個）。</param>
/// <param name="Packages">這個 UID 下的全部包名。</param>
/// <param name="Decision">決策。</param>
/// <param name="Reason">依據。</param>
/// <param name="RuleId">命中的規則。</param>
/// <param name="TamperedRuleId">因簽名不一致而被跳過的規則。</param>
public sealed record AppRouting(
    int Uid,
    string PackageName,
    IReadOnlyList<string> Packages,
    AppRoutingDecision Decision,
    AppRoutingReason Reason,
    Guid? RuleId = null,
    Guid? TamperedRuleId = null);

/// <summary>Android 上無法執行的規則的原因。</summary>
public enum RuleSkipReason : byte
{
    /// <summary>帶有域名、IP、端口、協議或網絡區域條件：黑洞式 VPN 只能按應用分流。</summary>
    UnsupportedConditions = 0,

    /// <summary>入站規則：不控制入站連接。</summary>
    Inbound = 1,
}

/// <summary>此平台無法執行的規則。</summary>
public sealed record SkippedRule(Guid RuleId, RuleSkipReason Reason);

/// <summary>路由規劃的結果。</summary>
/// <param name="Mode">規劃時的全局模式。</param>
/// <param name="BypassPackages">傳給 VpnService.Builder.addDisallowedApplication 的包名（允許的應用 + 自身）。</param>
/// <param name="ByUid">每個 UID 的決策。</param>
/// <param name="SkippedRules">無法執行的規則。</param>
public sealed record VpnRoutingPlan(
    FilterMode Mode,
    IReadOnlySet<string> BypassPackages,
    IReadOnlyDictionary<int, AppRouting> ByUid,
    IReadOnlyList<SkippedRule> SkippedRules,
    bool CloneAppsInheritPrimary = true)
{
    public static VpnRoutingPlan Empty { get; } = new(FilterMode.Ask, new HashSet<string>(StringComparer.Ordinal), new Dictionary<int, AppRouting>(), []);

    /// <summary>
    /// 依 UID 查找路由決定；開啟 <see cref="CloneAppsInheritPrimary"/> 時，多用戶 / 應用分身（UID >= 100000）
    /// 自動回退到主使用者的同 appId；關閉後不繼承主應用放行規則。
    /// </summary>
    public AppRouting? Find(int uid) =>
        ByUid.GetValueOrDefault(uid) ?? (CloneAppsInheritPrimary && uid >= 100_000 ? ByUid.GetValueOrDefault(uid % 100_000) : null);
}
