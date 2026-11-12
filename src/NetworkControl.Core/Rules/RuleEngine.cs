using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Rules;

/// <summary>規則集變更事件參數。</summary>
public sealed class RuleSetChangedEventArgs(RuleSet ruleSet) : EventArgs
{
    public RuleSet RuleSet { get; } = ruleSet;
}

/// <summary>
/// 持有當前生效的規則集快照。讀取無鎖；發布時編譯新快照並原子替換。
/// </summary>
public sealed class RuleEngine
{
    private readonly Lock _publishGate = new();
    private RuleSet _current = RuleSet.Empty;

    /// <summary>規則集被替換後觸發。RulePush 類引擎（Windows WFP）訂閱它來重新下發底層規則。</summary>
    public event EventHandler<RuleSetChangedEventArgs>? RuleSetChanged;

    public RuleSet Current => Volatile.Read(ref _current);

    public RuleMatch Evaluate(in EvaluationContext ctx) => Current.Evaluate(ctx);

    /// <summary>用給定的生效規則編譯並發布新的規則集。</summary>
    public RuleSet Publish(IEnumerable<Rule> activeRules)
    {
        RuleSet next;
        lock (_publishGate)
        {
            next = new RuleSet(_current.Version + 1, activeRules);
            Volatile.Write(ref _current, next);
        }

        RuleSetChanged?.Invoke(this, new RuleSetChangedEventArgs(next));
        return next;
    }
}
