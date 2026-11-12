using System.Net;
using System.Net.Sockets;
using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Matching;

/// <summary>按地址判斷所屬網絡區域。</summary>
public static class NetworkZones
{
    private static readonly IPNetwork[] LocalNetworks =
    [
        IPNetwork.Parse("10.0.0.0/8"),
        IPNetwork.Parse("172.16.0.0/12"),
        IPNetwork.Parse("192.168.0.0/16"),
        IPNetwork.Parse("169.254.0.0/16"),   // 鏈路本地
        IPNetwork.Parse("100.64.0.0/10"),    // CGNAT
        IPNetwork.Parse("224.0.0.0/4"),      // 組播
        IPNetwork.Parse("255.255.255.255/32"),
        IPNetwork.Parse("fc00::/7"),         // ULA
        IPNetwork.Parse("fe80::/10"),        // 鏈路本地
        IPNetwork.Parse("ff00::/8"),         // 組播
    ];

    /// <summary>「本地網絡」區域包含的網段（私有地址、鏈路本地、CGNAT、組播）。</summary>
    public static IReadOnlyList<IPNetwork> LocalNetworkRanges => LocalNetworks;

    public static NetworkZone Classify(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6)
        {
            address = address.MapToIPv4();
        }

        if (IPAddress.IsLoopback(address))
        {
            return NetworkZone.Loopback;
        }

        foreach (var network in LocalNetworks)
        {
            if (network.BaseAddress.AddressFamily == address.AddressFamily && network.Contains(address))
            {
                return NetworkZone.LocalNetwork;
            }
        }

        return NetworkZone.Internet;
    }

    /// <summary>判斷地址是否在網段內；自動處理 IPv4 映射的 IPv6 地址。</summary>
    public static bool Contains(IPNetwork network, IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6 && network.BaseAddress.AddressFamily == AddressFamily.InterNetwork)
        {
            address = address.MapToIPv4();
        }

        return network.BaseAddress.AddressFamily == address.AddressFamily && network.Contains(address);
    }
}
