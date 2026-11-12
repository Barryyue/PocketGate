using AtomUI.Localization;

namespace NetworkControl.App.Localization;

/// <summary>Tray 界面文本。只能在末尾追加成員；翻譯在同目錄的 XLIFF 文件中。</summary>
[LanguageCatalog]
public enum TrayLangResourceKind
{
    Open,
    Mode,
    Exit,
    TooltipDisconnected,
    TooltipMode,
    ShowRules,
    ShowLog,
    ShowSettings,
    BlockAll,
    Restore,
    DoNotDisturb,
    DoNotDisturbOff,
    ExitAll,
}
