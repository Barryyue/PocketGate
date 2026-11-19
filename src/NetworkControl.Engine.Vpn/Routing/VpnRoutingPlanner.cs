using NetworkControl.Core.Domain;
using NetworkControl.Core.Rules;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Vpn.Routing;

/// <summary>路由規劃的輸入。</summary>
public sealed record VpnRoutingInput
{
    /// <summary>生效規則集。</summary>
    public required RuleSet Rules { get; init; }

    public required FilterMode Mode { get; init; }

    public required IReadOnlyList<InstalledApp> Apps { get; init; }

    /// <summary>本應用的包名：始終排除在 VPN 之外。</summary>
    public required string SelfPackage { get; init; }

    /// <summary>多用戶 / 應用分身是否繼承主應用規則（預設 true）。</summary>
    public bool CloneAppsInheritPrimary { get; init; } = true;
}

/// <summary>
/// 把規則集和全局模式編譯成 VPN 分流名單（純函數，docs/android.md §5.4）。
/// 始終使用排除名單：默認進入 VPN（被阻斷），只有明確允許的應用被排除，新安裝的應用在重建前默認被阻斷。
/// </summary>
public static class VpnRoutingPlanner
{
    /// <summary>Android 的 Process.FIRST_APPLICATION_UID：小於它的是系統核心 UID。</summary>
    public const int FirstApplicationUid = 10_000;

    /// <summary>多用戶時 UID = 用戶 ID × 100000 + 應用 ID。</summary>
    public const int PerUserRange = 100_000;

    public static bool IsSystemUid(int uid) => uid % PerUserRange < FirstApplicationUid;

    public static VpnRoutingPlan Plan(VpnRoutingInput input)
    {
        ArgumentNullException.ThrowIfNull(input);

        var skipped = new List<SkippedRule>();
        foreach (var rule in input.Rules.Rules)
        {
            if (GetSkipReason(rule) is { } reason)
            {
                skipped.Add(new SkippedRule(rule.Id, reason));
            }
        }

        var bypass = new HashSet<string>(StringComparer.Ordinal) { input.SelfPackage };
        var byUid = new Dictionary<int, AppRouting>();

        foreach (var group in input.Apps.GroupBy(a => a.Uid))
        {
            var apps = group.OrderBy(a => a.PackageName, StringComparer.Ordinal).ToArray();
            var packages = apps.Select(a => a.PackageName).ToArray();
            var routing = Decide(input, group.Key, apps[0], packages);
            byUid[group.Key] = routing;
            if (routing.Decision == AppRoutingDecision.Bypass)
            {
                bypass.UnionWith(packages);
            }
        }

        return new VpnRoutingPlan(input.Mode, bypass, byUid, skipped, input.CloneAppsInheritPrimary);
    }

    /// <summary>規則能否在黑洞式 VPN 上執行：只能是「應用 + 出站 / 任意方向」，不能帶任何連接條件。</summary>
    public static bool IsEnforceable(Rule rule) => GetSkipReason(rule) is null;

    public static RuleSkipReason? GetSkipReason(Rule rule)
    {
        ArgumentNullException.ThrowIfNull(rule);
        var c = rule.Conditions;
        if (c.Direction == Direction.Inbound)
        {
            return RuleSkipReason.Inbound;
        }

        return c.Remote is null && c.RemotePort is null && c.LocalPort is null && c.Protocol is null
            ? null
            : RuleSkipReason.UnsupportedConditions;
    }

    /// <summary>構造用於規則匹配的應用身份。必須與 <see cref="PackageAppInspector"/> 為真實連接生成的身份一致。</summary>
    public static AppIdentity ToIdentity(InstalledApp app)
    {
        ArgumentNullException.ThrowIfNull(app);
        return new AppIdentity
        {
            AppId = 0,
            Path = PackageAppPath.ForPackage(app.PackageName),
            DisplayName = app.Label,
            Signature = PackageAppInspector.ToSignature(app),
        };
    }

    private static AppRouting Decide(VpnRoutingInput input, int uid, InstalledApp primary, string[] packages)
    {
        if (packages.Contains(input.SelfPackage, StringComparer.Ordinal))
        {
            return new AppRouting(uid, primary.PackageName, packages, AppRoutingDecision.Bypass, AppRoutingReason.Self);
        }

        if (IsSystemUid(uid))
        {
            return new AppRouting(uid, primary.PackageName, packages, AppRoutingDecision.Bypass, AppRoutingReason.SystemUid);
        }

        switch (input.Mode)
        {
            case FilterMode.Disabled:
                return new AppRouting(uid, primary.PackageName, packages, AppRoutingDecision.Bypass, AppRoutingReason.Mode);
            case FilterMode.BlockAll:
                return new AppRouting(uid, primary.PackageName, packages, AppRoutingDecision.Block, AppRoutingReason.Mode);
        }

        var match = input.Rules.FindAppRule(ToIdentity(primary), IsEnforceable);
        if (match.Rule is { } rule)
        {
            var decision = rule.Action == VerdictAction.Allow ? AppRoutingDecision.Bypass : AppRoutingDecision.Block;
            return new AppRouting(uid, primary.PackageName, packages, decision, AppRoutingReason.Rule, rule.Id, match.TamperedRule?.Id);
        }

        // 篡改（簽名變化）的應用按「未決定」處理：即使在黑名單模式下也不放行，並在聯網時觸發篡改告警
        var tampered = match.TamperedRule is not null;
        var fallback = input.Mode switch
        {
            FilterMode.Blacklist when !tampered => AppRoutingDecision.Bypass,
            FilterMode.Ask or FilterMode.Blacklist => AppRoutingDecision.BlockAndAsk,
            _ => AppRoutingDecision.Block,
        };

        return new AppRouting(uid, primary.PackageName, packages, fallback, AppRoutingReason.Mode, TamperedRuleId: match.TamperedRule?.Id);
    }
}
