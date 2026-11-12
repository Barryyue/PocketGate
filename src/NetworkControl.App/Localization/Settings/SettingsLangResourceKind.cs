using AtomUI.Localization;

namespace NetworkControl.App.Localization;

/// <summary>Settings 界面文本。只能在末尾追加成員；翻譯在同目錄的 XLIFF 文件中。</summary>
[LanguageCatalog]
public enum SettingsLangResourceKind
{
    Title,
    SectionInterface,
    Language,
    LanguageFollowSystem,
    Theme,
    ThemeFollowSystem,
    ThemeLight,
    ThemeDark,
    LaunchAtLogin,
    SectionPrompt,
    PromptTimeout,
    DefaultAction,
    SectionSafety,
    FailPolicy,
    FailOpen,
    FailClosed,
    AllowIcmp,
    SectionData,
    LogRetention,
    ServiceSettingsHint,
    Saved,
    SectionDiagnostics,
    DiagnosticsHint,
    DiagnosticsIncludeConfiguration,
    DiagnosticsIncludeActivity,
    DiagnosticsExport,
    DiagnosticsOpenLogFolder,
    DiagnosticsPickerTitle,
    DiagnosticsExported,
    DiagnosticsExportedPartial,
    DiagnosticsExportFailed,
    DiagnosticsCommandLineHint,
}
