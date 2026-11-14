using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>詳情對話框中的一行。</summary>
public sealed record DetailField(string Label, string Value, bool Monospace = false);

/// <summary>連線記錄詳情：完整字段，並用一句話解釋為什麼是這個判決。</summary>
public sealed class LogDetailViewModel(LogItemViewModel item, DisplayText text) : LocalizedViewModel(text), IValidatingDialogContent
{
    public string VerdictText => item.VerdictText;

    public string VerdictColor => item.VerdictColor;

    public string Title => item.Host.Length > 0 ? item.Host : item.Remote;

    public string Explanation => Loc.Get(item.Verdict.Reason switch
    {
        Proto.VerdictReason.Mode => LogLangResourceKind.ExplainMode,
        Proto.VerdictReason.Prompt => LogLangResourceKind.ExplainPrompt,
        Proto.VerdictReason.PromptTimeout => LogLangResourceKind.ExplainPromptTimeout,
        Proto.VerdictReason.Builtin => LogLangResourceKind.ExplainBuiltin,
        Proto.VerdictReason.NoUserSession => LogLangResourceKind.ExplainNoUserSession,
        Proto.VerdictReason.DoNotDisturb => LogLangResourceKind.ExplainDoNotDisturb,
        Proto.VerdictReason.FailSafe => LogLangResourceKind.ExplainFailSafe,
        Proto.VerdictReason.Cancelled => LogLangResourceKind.ExplainCancelled,
        _ => LogLangResourceKind.ExplainRule,
    });

    public IReadOnlyList<DetailField> Fields
    {
        get
        {
            var fields = new List<DetailField>
            {
                new(Loc.Get(LogLangResourceKind.ColumnTime), item.TimeText),
                new(Loc.Get(LogLangResourceKind.ColumnApp), item.AppName),
            };
            if (item.HasApp)
            {
                fields.Add(new(Loc.Get(LogLangResourceKind.FieldPath), item.AppPathValue, Monospace: true));
            }

            fields.Add(new(Loc.Get(LogLangResourceKind.ColumnDirection), item.DirectionText));
            fields.Add(new(Loc.Get(LogLangResourceKind.ColumnProtocol), item.ProtocolText));
            if (item.LocalEndpoint is not null)
            {
                fields.Add(new(Loc.Get(LogLangResourceKind.FieldLocalEndpoint), item.Local, Monospace: true));
            }

            fields.Add(new(Loc.Get(LogLangResourceKind.ColumnRemote), item.Remote, Monospace: true));
            if (item.Host.Length > 0)
            {
                fields.Add(new(Loc.Get(LogLangResourceKind.ColumnHost), item.Host, Monospace: true));
            }

            fields.Add(new(Loc.Get(LogLangResourceKind.ColumnReason), item.ReasonText));
            if (item.Verdict.HasRuleId)
            {
                fields.Add(new(Loc.Get(LogLangResourceKind.FieldRule), item.Verdict.RuleId, Monospace: true));
            }

            fields.Add(new(Loc.Get(LogLangResourceKind.ColumnCount), item.Count.ToString("N0", Loc.Culture)));
            return fields;
        }
    }

    public bool Validate() => true;
}
