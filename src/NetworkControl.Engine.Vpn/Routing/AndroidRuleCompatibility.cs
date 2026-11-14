using NetworkControl.Core.Domain;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Vpn.Routing;

/// <summary>規則在 Android 黑洞式 VPN 上不生效的原因。</summary>
public enum AndroidRuleIneffectiveReason : byte
{
    /// <summary>帶有域名、IP、端口、協議或網絡區域條件：只能按應用分流。</summary>
    UnsupportedConditions = 0,

    /// <summary>入站規則：不控制入站連接。</summary>
    Inbound = 1,

    /// <summary>應用路徑不是 <c>android-app://&lt;包名&gt;</c>（例如桌面程序路徑），永遠匹配不到 Android 應用。</summary>
    NotAndroidApp = 2,
}

/// <summary>
/// 規則在 Android 上是否會生效（純函數）。與 <see cref="VpnRoutingPlanner.GetSkipReason"/> 的區別：
/// 後者只描述引擎無法執行的條件，這裡額外排除桌面應用路徑——它們不會出現在任何 Android 應用的身份上。
/// 規則導入預覽用它統計「N 條規則在 Android 上不生效」。
/// </summary>
public static class AndroidRuleCompatibility
{
    public static bool IsEffectiveOnAndroid(Rule rule) => GetIneffectiveReason(rule) is null;

    public static AndroidRuleIneffectiveReason? GetIneffectiveReason(Rule rule)
    {
        ArgumentNullException.ThrowIfNull(rule);
        if (VpnRoutingPlanner.GetSkipReason(rule) is { } reason)
        {
            return reason == RuleSkipReason.Inbound
                ? AndroidRuleIneffectiveReason.Inbound
                : AndroidRuleIneffectiveReason.UnsupportedConditions;
        }

        return rule.App is { } app && !PackageAppPath.TryGetPackage(app.PathPattern, out _)
            ? AndroidRuleIneffectiveReason.NotAndroidApp
            : null;
    }

    public static int CountIneffective(IEnumerable<Rule> rules)
    {
        ArgumentNullException.ThrowIfNull(rules);
        return rules.Count(rule => !IsEffectiveOnAndroid(rule));
    }
}
