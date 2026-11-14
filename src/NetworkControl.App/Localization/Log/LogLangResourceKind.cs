using AtomUI.Localization;

namespace NetworkControl.App.Localization;

/// <summary>Log 界面文本。只能在末尾追加成員；翻譯在同目錄的 XLIFF 文件中。</summary>
[LanguageCatalog]
public enum LogLangResourceKind
{
    Title,
    SearchPlaceholder,
    Live,
    Empty,
    ColumnTime,
    ColumnApp,
    ColumnDirection,
    ColumnProtocol,
    ColumnRemote,
    ColumnHost,
    ColumnVerdict,
    ColumnReason,
    ColumnCount,
    ColumnOperations,
    CreateRule,
    Subtitle,
    Details,
    DetailTitle,
    FieldPath,
    FieldRule,
    ExplainRule,
    ExplainMode,
    ExplainPrompt,
    ExplainPromptTimeout,
    ExplainBuiltin,
    ExplainNoUserSession,
    ExplainDoNotDisturb,
    ExplainFailSafe,
    ExplainCancelled,
    ColumnLocal,
    FieldLocalEndpoint,
}
