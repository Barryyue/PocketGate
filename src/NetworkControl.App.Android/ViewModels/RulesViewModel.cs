using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Core.Domain;

namespace NetworkControl.Android.ViewModels;

public sealed partial class RuleItemViewModel : ObservableObject
{
    private readonly IBackend _backend;

    public RuleItemViewModel(Rule rule, IBackend backend, LocalizationService loc)
    {
        Rule = rule;
        _backend = backend;
        TargetLabel = rule.App?.PathPattern ?? "*";
        ActionLabel = rule.Action == VerdictAction.Allow
            ? loc.Get(CommonLangResourceKind.Allow)
            : loc.Get(CommonLangResourceKind.Block);
        IsAllow = rule.Action == VerdictAction.Allow;
        ScopeLabel = BuildScopeLabel(rule);
        IsEnabled = rule.Enabled;
    }

    public Rule Rule { get; }
    public string TargetLabel { get; }
    public string ActionLabel { get; }
    public bool IsAllow { get; }
    public string ScopeLabel { get; }

    [ObservableProperty]
    public partial bool IsEnabled { get; set; }

    [RelayCommand]
    private Task ToggleAsync() => _backend.ToggleRuleEnabledAsync(Rule.Id, CancellationToken.None);

    [RelayCommand]
    private Task DeleteAsync() => _backend.DeleteRuleAsync(Rule.Id, CancellationToken.None);

    private static string BuildScopeLabel(Rule rule)
    {
        var parts = new List<string> { rule.Lifetime.ToString() };
        if (rule.Conditions.Remote is { } r) parts.Add(r.ToString() ?? string.Empty);
        if (rule.Conditions.RemotePort is { } p) parts.Add($":{p}");
        return string.Join(" · ", parts.Where(s => !string.IsNullOrEmpty(s)));
    }
}

public sealed partial class RulesViewModel : LocalizedViewModel
{
    public RulesViewModel(UiContext context)
        : base(context)
    {
        Refresh();
    }

    public ObservableCollection<RuleItemViewModel> Items { get; } = [];

    [ObservableProperty]
    public partial string SearchText { get; set; } = string.Empty;

    public bool IsEmpty => Items.Count == 0;

    partial void OnSearchTextChanged(string value) => Refresh();

    public void Refresh()
    {
        var q = SearchText.Trim();
        Items.Clear();
        foreach (var rule in Backend.Rules)
        {
            var item = new RuleItemViewModel(rule, Backend, Loc);
            if (!string.IsNullOrEmpty(q) &&
                !item.TargetLabel.Contains(q, StringComparison.OrdinalIgnoreCase) &&
                !item.ScopeLabel.Contains(q, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            Items.Add(item);
        }

        OnPropertyChanged(nameof(IsEmpty));
    }

    [RelayCommand]
    private Task ClearExpiredAsync() => Backend.ClearExpiredRulesAsync(CancellationToken.None);
}
