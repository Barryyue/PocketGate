namespace NetworkControl.Core.Profiles;

/// <summary>當前主要網絡的類型。</summary>
public enum NetworkKind : byte
{
    None = 0,
    Wired = 1,
    Wifi = 2,
    Cellular = 3,
    Vpn = 4,
    Other = 5,
}

/// <summary>
/// 【平台實現】當前主要網絡。字段拿不到時為 null（例如沒有定位權限時 Android 讀不到 SSID）。
/// </summary>
public sealed record NetworkContext(NetworkKind Kind, string? Ssid, string? NetworkId, string? DisplayName)
{
    public static NetworkContext None { get; } = new(NetworkKind.None, null, null, null);

    /// <summary>用於記住「用戶在這個網絡上手動選過配置文件」的指紋。</summary>
    public string Fingerprint => $"{Kind}|{Ssid ?? ""}|{NetworkId ?? ""}";
}

/// <summary>【平台實現】監聽主要網絡變化；事件可能在後台線程觸發。</summary>
public interface INetworkMonitor
{
    NetworkContext Current { get; }

    event EventHandler? Changed;
}

/// <summary>自動激活條件；序列化為 profiles.auto_activate 列的 JSON。</summary>
public sealed record AutoActivateCondition(
    NetworkKind? Kind,
    IReadOnlyList<string> Ssids,
    IReadOnlyList<string> NetworkIds)
{
    public static AutoActivateCondition Empty { get; } = new(null, [], []);

    public bool IsEmpty => Kind is null && Ssids.Count == 0 && NetworkIds.Count == 0;

    public bool Matches(NetworkContext context)
    {
        if (IsEmpty)
        {
            return false;
        }

        if (Kind is { } kind && context.Kind != kind)
        {
            return false;
        }

        if (Ssids.Count > 0
            && (context.Ssid is null || !Ssids.Contains(context.Ssid, StringComparer.OrdinalIgnoreCase)))
        {
            return false;
        }

        if (NetworkIds.Count > 0
            && (context.NetworkId is null || !NetworkIds.Contains(context.NetworkId, StringComparer.Ordinal)))
        {
            return false;
        }

        return true;
    }
}
