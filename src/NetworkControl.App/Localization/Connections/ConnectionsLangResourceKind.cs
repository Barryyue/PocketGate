using AtomUI.Localization;

namespace NetworkControl.App.Localization;

/// <summary>即時連線頁面文本。只能在末尾追加成員。</summary>
[LanguageCatalog]
public enum ConnectionsLangResourceKind
{
    Title,
    Subtitle,
    SearchPlaceholder,
    IncludeListening,
    IncludeLoopback,
    Empty,
    Unsupported,
    ColumnApp,
    ColumnProtocol,
    ColumnLocal,
    ColumnRemote,
    ColumnState,
    ColumnPid,
    StateListen,
    StateEstablished,
    StateSynSent,
    StateSynReceived,
    StateClosing,
    StateTimeWait,
    StateClosed,
    StateUdp,
    ColumnOperations,
    ViewLog,
    CreateRule,
    Disconnect,
    DisconnectConfirmTitle,
    DisconnectConfirmContent,
    DisconnectAndDeny,
}
