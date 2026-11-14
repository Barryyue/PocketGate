namespace NetworkControl.Core.Domain;

/// <summary>全局過濾模式。</summary>
public enum FilterMode : byte
{
    /// <summary>無規則命中時彈窗詢問（默認）。</summary>
    Ask = 0,

    /// <summary>無規則命中時拒絕。</summary>
    Whitelist = 1,

    /// <summary>無規則命中時允許。</summary>
    Blacklist = 2,

    /// <summary>忽略規則，只放行內置必要流量。</summary>
    BlockAll = 3,

    /// <summary>停用過濾，全部允許（仍然記錄日誌）。</summary>
    Disabled = 4,
}

/// <summary>失效策略：服務異常時放行還是阻斷。</summary>
public enum FailPolicy : byte
{
    FailOpen = 0,
    FailClosed = 1,
}

/// <summary>配置文件：一組規則和模式的集合。</summary>
public sealed record Profile
{
    /// <summary>首次安裝時創建的默認配置文件 ID。</summary>
    public static readonly Guid DefaultId = new("00000000-0000-0000-0000-000000000001");

    public required Guid Id { get; init; }

    public required string Name { get; init; }

    public FilterMode Mode { get; init; } = FilterMode.Ask;

    public int SortOrder { get; init; }

    /// <summary>自動激活條件；null 表示不自動切換。</summary>
    public NetworkControl.Core.Profiles.AutoActivateCondition? AutoActivate { get; init; }
}
