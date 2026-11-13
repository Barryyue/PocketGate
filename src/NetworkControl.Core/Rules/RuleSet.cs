using System.Collections.Frozen;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;

namespace NetworkControl.Core.Rules;

/// <summary>
/// 不可變的規則集快照。規則變更時整體重新編譯並原子替換，求值過程無鎖。
/// </summary>
public sealed class RuleSet
{
    private readonly FrozenDictionary<string, CompiledRule[]> _byExactPath;
    private readonly CompiledRule[] _wildcardAppRules;
    private readonly CompiledRule[] _globalRules;

    internal RuleSet(long version, IEnumerable<Rule> rules)
    {
        Version = version;

        var compiled = rules.Select(r => new CompiledRule(r)).ToList();
        compiled.Sort(RulePriority.Comparer);
        for (var i = 0; i < compiled.Count; i++)
        {
            compiled[i].Rank = i;
        }

        Rules = compiled.Select(c => c.Rule).ToArray();

        var pathComparer = PathPattern.DefaultIgnoreCase ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;
        _byExactPath = compiled
            .Where(c => c.ExactPathKey is not null)
            .GroupBy(c => c.ExactPathKey!, pathComparer)
            .ToFrozenDictionary(g => g.Key, g => g.ToArray(), pathComparer);
        _wildcardAppRules = compiled.Where(c => !c.IsGlobal && c.ExactPathKey is null).ToArray();
        _globalRules = compiled.Where(c => c.IsGlobal).ToArray();
    }

    public static RuleSet Empty { get; } = new(0, []);

    public long Version { get; }

    /// <summary>按優先級排序的生效規則。</summary>
    public IReadOnlyList<Rule> Rules { get; }

    public int Count => Rules.Count;

    /// <summary>按優先級順序找到第一條同時匹配應用和條件的規則。</summary>
    public RuleMatch Evaluate(in EvaluationContext ctx)
    {
        var exact = Array.Empty<CompiledRule>();
        if (ctx.App is { } app && _byExactPath.TryGetValue(PathPattern.Normalize(app.Path), out var found))
        {
            exact = found;
        }

        // 三個候選列表各自已按 Rank 排序，這裡做三路歸併，保證全局優先級順序
        int i = 0, j = 0, k = 0;
        Rule? tampered = null;
        while (true)
        {
            var next = PickNext(exact, ref i, _wildcardAppRules, ref j, _globalRules, ref k);
            if (next is null)
            {
                return new RuleMatch(null, tampered);
            }

            if (!next.MatchConditions(ctx))
            {
                continue;
            }

            switch (next.MatchApp(ctx.App))
            {
                case AppMatchOutcome.Match:
                    return new RuleMatch(next.Rule, tampered);
                case AppMatchOutcome.Tampered:
                    tampered ??= next.Rule;
                    break;
            }
        }
    }

    /// <summary>
    /// 按優先級順序找到第一條匹配給定應用、且通過 <paramref name="includeRule"/> 篩選的規則（不看連接條件）。
    /// 供只能按應用分流的引擎（Android 黑洞式 VPN）把規則集編譯成分流名單；篡改的規則跳過並記入 <see cref="RuleMatch.TamperedRule"/>。
    /// </summary>
    public RuleMatch FindAppRule(AppIdentity app, Func<Rule, bool> includeRule)
    {
        ArgumentNullException.ThrowIfNull(app);
        ArgumentNullException.ThrowIfNull(includeRule);

        var exact = _byExactPath.TryGetValue(PathPattern.Normalize(app.Path), out var found) ? found : [];
        int i = 0, j = 0, k = 0;
        Rule? tampered = null;
        while (PickNext(exact, ref i, _wildcardAppRules, ref j, _globalRules, ref k) is { } next)
        {
            if (!includeRule(next.Rule))
            {
                continue;
            }

            switch (next.MatchApp(app))
            {
                case AppMatchOutcome.Match:
                    return new RuleMatch(next.Rule, tampered);
                case AppMatchOutcome.Tampered:
                    tampered ??= next.Rule;
                    break;
            }
        }

        return new RuleMatch(null, tampered);
    }

    private static CompiledRule? PickNext(
        CompiledRule[] a, ref int i,
        CompiledRule[] b, ref int j,
        CompiledRule[] c, ref int k)
    {
        var ra = i < a.Length ? a[i].Rank : int.MaxValue;
        var rb = j < b.Length ? b[j].Rank : int.MaxValue;
        var rc = k < c.Length ? c[k].Rank : int.MaxValue;

        if (ra == int.MaxValue && rb == int.MaxValue && rc == int.MaxValue)
        {
            return null;
        }

        if (ra <= rb && ra <= rc)
        {
            return a[i++];
        }

        return rb <= rc ? b[j++] : c[k++];
    }
}

/// <summary>
/// 規則優先級：手動置頂 → 具體度 → 同分時拒絕優先 → 更新時間較新 → ID（保證確定性）。
/// </summary>
internal static class RulePriority
{
    public static readonly IComparer<CompiledRule> Comparer = Comparer<CompiledRule>.Create(Compare);

    private static int Compare(CompiledRule? x, CompiledRule? y)
    {
        if (ReferenceEquals(x, y))
        {
            return 0;
        }

        if (x is null)
        {
            return 1;
        }

        if (y is null)
        {
            return -1;
        }

        var c = y.Rule.ManualPriority.CompareTo(x.Rule.ManualPriority);
        if (c != 0)
        {
            return c;
        }

        c = y.Specificity.CompareTo(x.Specificity);
        if (c != 0)
        {
            return c;
        }

        // Deny = 1 排在 Allow = 0 之前
        c = ((byte)y.Rule.Action).CompareTo((byte)x.Rule.Action);
        if (c != 0)
        {
            return c;
        }

        c = y.Rule.UpdatedAt.CompareTo(x.Rule.UpdatedAt);
        return c != 0 ? c : x.Rule.Id.CompareTo(y.Rule.Id);
    }
}
