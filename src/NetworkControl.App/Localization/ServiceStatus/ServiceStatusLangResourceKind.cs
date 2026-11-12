using AtomUI.Localization;

namespace NetworkControl.App.Localization;

/// <summary>ServiceStatus 界面文本。只能在末尾追加成員；翻譯在同目錄的 XLIFF 文件中。</summary>
[LanguageCatalog]
public enum ServiceStatusLangResourceKind
{
    Title,
    Description,
    ReasonNotRunning,
    ReasonAccessDenied,
    ReasonProtocolMismatch,
    ReasonOther,
    StartService,
    StartDevService,
    Retry,
    StartFailed,
    HintDev,
    HintInstalled,
    HintNotInstalled,
    ExportDiagnosticsHint,
}
