using AtomUI.Localization;

namespace NetworkControl.App.Localization;

/// <summary>Apps（應用頁） 界面文本。只能在末尾追加成員；翻譯在同目錄的 XLIFF 文件中。</summary>
[LanguageCatalog]
public enum AppsLangResourceKind
{
    Title,
    Subtitle,
    SearchPlaceholder,
    ColumnApp,
    ColumnTrust,
    ColumnAllowed,
    ColumnBlocked,
    ColumnRules,
    ColumnLastSeen,
    ColumnOperations,
    TrustUnsigned,
    TrustSystem,
    TrustUnknown,
    RulesNone,
    RulesSummary,
    AllowApp,
    DenyApp,
    ViewLog,
    ViewRules,
    Empty,
    CountSummary,
    LastSeenJustNow,
    LastSeenMinutes,
    LastSeenHours,
    AllowTooltip,
    DenyTooltip,
}
