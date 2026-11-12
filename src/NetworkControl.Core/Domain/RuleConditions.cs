using System.Net;

namespace NetworkControl.Core.Domain;

/// <summary>
/// 規則的匹配條件。所有非 null 的條件必須同時滿足。
/// 新增條件類型時：在此添加屬性 → 在 <c>Rules.CompiledRule</c> 中編譯 → 在 <c>Rules.RuleSpecificity</c> 中計分。
/// </summary>
public sealed record RuleConditions
{
    public static readonly RuleConditions Any = new();

    public Direction? Direction { get; init; }

    public L4Protocol? Protocol { get; init; }

    public RemoteTarget? Remote { get; init; }

    public PortRange? RemotePort { get; init; }

    /// <summary>本地端口，入站規則常用。</summary>
    public PortRange? LocalPort { get; init; }
}

/// <summary>網絡區域。</summary>
public enum NetworkZone : byte
{
    Loopback = 0,
    LocalNetwork = 1,
    Internet = 2,
}

/// <summary>遠端目標，多選一。</summary>
public abstract record RemoteTarget;

/// <summary>域名目標：精確域名（api.github.com）或通配域名（*.github.com，同時匹配 github.com 本身）。</summary>
public sealed record DomainTarget(string Pattern) : RemoteTarget;

/// <summary>地址目標：一個或多個 IP 網段，單個 IP 即 /32 或 /128。</summary>
public sealed record AddressTarget(IReadOnlyList<IPNetwork> Networks) : RemoteTarget
{
    public static AddressTarget Single(IPAddress address) =>
        new([new IPNetwork(address, address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork ? 32 : 128)]);
}

/// <summary>網絡區域目標。</summary>
public sealed record ZoneTarget(NetworkZone Zone) : RemoteTarget;
