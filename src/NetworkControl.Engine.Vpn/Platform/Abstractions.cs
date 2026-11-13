using System.Net;
using NetworkControl.Core.Domain;
using NetworkControl.Engine.Vpn.Routing;

namespace NetworkControl.Engine.Vpn.Platform;

/// <summary>已安裝的應用（只包含申請了聯網權限的應用）。</summary>
/// <param name="PackageName">包名。</param>
/// <param name="Uid">Linux UID；共享 UID 的多個包具有相同的值。</param>
/// <param name="Label">應用標籤（顯示名稱）。</param>
/// <param name="IsSystem">是否為系統應用。</param>
/// <param name="VersionCode">版本碼，用於判斷應用是否升級。</param>
/// <param name="LastUpdated">最後一次安裝或更新的時間。</param>
/// <param name="SignerSha256">簽名證書 SHA-256（小寫十六進制，多個簽名者排序後以逗號連接）；取不到時為 null。</param>
public sealed record InstalledApp(
    string PackageName,
    int Uid,
    string Label,
    bool IsSystem,
    long VersionCode,
    DateTimeOffset LastUpdated,
    string? SignerSha256);

/// <summary>【平台實現】已安裝應用目錄（Android：PackageManager）。</summary>
public interface IInstalledAppCatalog
{
    /// <summary>應用列表發生變化（安裝、卸載、更新）後觸發。</summary>
    event EventHandler? Changed;

    /// <summary>當前快照。實現應緩存結果，在 <see cref="Changed"/> 時刷新。</summary>
    IReadOnlyList<InstalledApp> GetApps();

    /// <summary>某個 UID 下的全部包名；未知 UID 返回空列表。</summary>
    IReadOnlyList<string> GetPackagesForUid(int uid);

    /// <summary>按包名查找；應用不存在時返回 null。</summary>
    InstalledApp? Find(string packageName);
}

/// <summary>【平台實現】報文所屬的 UID（Android：ConnectivityManager.getConnectionOwnerUid）。</summary>
public interface IConnectionOwnerResolver
{
    /// <summary>查不到時返回 null。</summary>
    int? GetOwnerUid(L4Protocol protocol, IPEndPoint local, IPEndPoint remote);
}

/// <summary>虛擬網卡狀態。</summary>
public enum VpnTunnelState : byte
{
    /// <summary>未建立。</summary>
    Closed = 0,

    /// <summary>正在建立或重建。</summary>
    Establishing = 1,

    /// <summary>已建立並在讀取報文。</summary>
    Active = 2,

    /// <summary>用戶尚未授權本應用建立 VPN。</summary>
    NotPrepared = 3,

    /// <summary>被系統撤銷（用戶啟用了其他 VPN，或在系統設定中斷開）。</summary>
    Revoked = 4,

    /// <summary>建立失敗。</summary>
    Failed = 5,
}

/// <summary>建立虛擬網卡所需的配置。</summary>
/// <param name="BypassPackages">排除在 VPN 之外（即允許聯網）的包名。</param>
public sealed record VpnTunnelConfig(IReadOnlySet<string> BypassPackages)
{
    public const int DefaultMtu = 1500;

    public int Mtu { get; init; } = DefaultMtu;

    /// <summary>判斷兩個配置是否會產生相同的虛擬網卡（相同時不需要重建）。</summary>
    public bool IsEquivalentTo(VpnTunnelConfig? other) =>
        other is not null && other.Mtu == Mtu && other.BypassPackages.SetEquals(BypassPackages);

    public static VpnTunnelConfig From(VpnRoutingPlan plan) => new(plan.BypassPackages);
}

/// <summary>接收從虛擬網卡讀到的報文。在平台的讀線程上調用，實現必須快速返回。</summary>
public interface IPacketSink
{
    void OnPacket(ReadOnlySpan<byte> packet);
}

/// <summary>【平台實現】虛擬網卡（Android：VpnService）。</summary>
public interface IVpnTunnel
{
    event EventHandler? StateChanged;

    VpnTunnelState State { get; }

    /// <summary>最近一次失敗或撤銷的說明。</summary>
    string? LastError { get; }

    /// <summary>用戶是否已授權本應用建立 VPN（Android：VpnService.prepare 返回 null）。</summary>
    bool IsPrepared { get; }

    /// <summary>建立虛擬網卡；已建立時按新配置重建（新網卡建立後再關閉舊的）。</summary>
    Task ApplyAsync(VpnTunnelConfig config, IPacketSink sink, CancellationToken cancellationToken);

    Task CloseAsync(CancellationToken cancellationToken);
}

/// <summary>【平台實現】用戶的「保護」開關意圖（Android：SharedPreferences）。與過濾模式無關：關閉保護 = 不建立虛擬網卡。</summary>
public interface IProtectionSwitch
{
    event EventHandler? Changed;

    bool IsEnabled { get; }

    void SetEnabled(bool enabled);
}

/// <summary>內存中的保護開關（測試和沒有持久化需求的宿主使用）。</summary>
public sealed class InMemoryProtectionSwitch(bool enabled = false) : IProtectionSwitch
{
    private volatile bool _enabled = enabled;

    public event EventHandler? Changed;

    public bool IsEnabled => _enabled;

    public void SetEnabled(bool enabled)
    {
        if (_enabled == enabled)
        {
            return;
        }

        _enabled = enabled;
        Changed?.Invoke(this, EventArgs.Empty);
    }
}
