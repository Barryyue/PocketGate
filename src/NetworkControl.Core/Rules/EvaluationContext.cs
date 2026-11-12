using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Rules;

/// <summary>規則求值所需的全部輸入，均已預先解析好，求值過程中不做任何 I/O。</summary>
public readonly record struct EvaluationContext(
    FlowInfo Flow,
    AppIdentity? App,
    DomainCandidates Domains,
    NetworkZone RemoteZone);

/// <summary>規則求值結果。</summary>
/// <param name="Rule">命中的規則；為 null 表示沒有規則命中。</param>
/// <param name="TamperedRule">因程序被篡改（簽名或哈希不一致）而被跳過的規則。</param>
public readonly record struct RuleMatch(Rule? Rule, Rule? TamperedRule)
{
    public static readonly RuleMatch None = new(null, null);

    public bool IsMatch => Rule is not null;
}

/// <summary>應用匹配結果。</summary>
public enum AppMatchOutcome : byte
{
    NoMatch = 0,
    Match = 1,

    /// <summary>路徑匹配，但簽名者或哈希不一致：程序可能已被替換。</summary>
    Tampered = 2,
}
