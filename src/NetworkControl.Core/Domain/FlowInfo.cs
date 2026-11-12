using System.Net;

namespace NetworkControl.Core.Domain;

/// <summary>連接信息的來源類型，決定了判決能否影響這條連接本身。</summary>
public enum FlowOrigin : byte
{
    /// <summary>引擎已掛起連接，等待判決結果（Linux NFQUEUE、macOS NE、Windows 驅動）。</summary>
    Pending = 0,

    /// <summary>連接已被默認阻斷規則攔下，僅用於觸發詢問（Windows 用戶態 WFP）。</summary>
    BlockedEvent = 1,
}

/// <summary>引擎上報的一條網絡連接（流）。</summary>
public sealed record FlowInfo
{
    /// <summary>引擎內唯一的連接 ID。</summary>
    public required ulong FlowId { get; init; }

    public required Direction Direction { get; init; }

    public required L4Protocol Protocol { get; init; }

    public required IPEndPoint Local { get; init; }

    public required IPEndPoint Remote { get; init; }

    /// <summary>發起連接的進程；部分引擎可能拿不到。</summary>
    public ProcessRef? Process { get; init; }

    /// <summary>引擎直接提供的可執行文件路徑（例如 WFP AppId、macOS audit token 解析結果）。</summary>
    public string? ExecutablePath { get; init; }

    /// <summary>發起連接的用戶（Windows SID / Unix UID）。</summary>
    public string? UserId { get; init; }

    /// <summary>引擎直接提供的遠端主機名（macOS remoteHostname）。</summary>
    public string? EngineHostname { get; init; }

    public required DateTimeOffset Timestamp { get; init; }

    public FlowOrigin Origin { get; init; } = FlowOrigin.Pending;
}
