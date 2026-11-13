using AtomUI.Localization;

namespace NetworkControl.App.Localization;

/// <summary>Shell 界面文本。只能在末尾追加成員；翻譯在同目錄的 XLIFF 文件中。</summary>
[LanguageCatalog]
public enum ShellLangResourceKind
{
    AppTitle,
    NavOverview,
    NavRules,
    NavLog,
    NavSettings,
    StatusConnecting,
    StatusConnected,
    StatusDisconnected,
    StatusEngine,
    Quit,
    NavApps,
    StatusPendingPrompts,
}
