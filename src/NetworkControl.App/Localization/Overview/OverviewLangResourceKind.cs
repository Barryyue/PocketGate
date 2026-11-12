using AtomUI.Localization;

namespace NetworkControl.App.Localization;

/// <summary>Overview 界面文本。只能在末尾追加成員；翻譯在同目錄的 XLIFF 文件中。</summary>
[LanguageCatalog]
public enum OverviewLangResourceKind
{
    Mode,
    Engine,
    ActiveRules,
    PendingPrompts,
    EngineHoldsConnections,
    EngineBlocksFirst,
    SwitchMode,
    BlockAll,
    Restore,
    BlockAllActive,
    DoNotDisturb,
    DoNotDisturbOff,
    DoNotDisturbUntil,
    SimTitle,
    SimDescription,
    SimApp,
    SimHost,
    SimPort,
    SimProtocol,
    SimConnect,
    SimExitApp,
    SimResults,
    EngineFake,
    EngineWfp,
    EngineSwitchHint,
    EngineInstalledHint,
    EngineSwitching,
    EngineSwitchFailed,
}
