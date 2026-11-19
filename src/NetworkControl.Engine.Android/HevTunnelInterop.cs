using System.Runtime.InteropServices;

namespace NetworkControl.Engine.Android;

/// <summary>
/// 原生 <c>libhev-socks5-tunnel.so</c> P/Invoke 封裝與 YAML 設定檔產生器。
/// </summary>
public static partial class HevTunnelInterop
{
    private const string LibName = "hev-socks5-tunnel";

    [LibraryImport(LibName, EntryPoint = "hev_socks5_tunnel_main_from_file", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial int MainFromFile(string configPath, int tunFd);

    [LibraryImport(LibName, EntryPoint = "hev_socks5_tunnel_quit")]
    internal static partial void Quit();

    [LibraryImport(LibName, EntryPoint = "hev_socks5_tunnel_stats")]
    internal static unsafe partial void GetStats(ulong* txPackets, ulong* txBytes, ulong* rxPackets, ulong* rxBytes);

    /// <summary>
    /// 建立 hev-socks5-tunnel 設定檔（一律使用 socks5.udp: 'udp' 與 mapdns，確保兼容 FlClash / mihomo / 內建直連出口）。
    /// </summary>
    public static string WriteConfig(
        string path,
        string socks5Address,
        int socks5Port,
        string? username = null,
        string? password = null,
        int mtu = 1500,
        string? mapDnsAddress = "198.18.0.2")
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var lines = new List<string>
        {
            "tunnel:",
            $"  mtu: {mtu}",
            "  ipv4: 10.111.222.1",
            "socks5:",
            $"  port: {socks5Port}",
            $"  address: '{EscapeYaml(socks5Address)}'",
            "  udp: 'udp'",
        };
        if (!string.IsNullOrEmpty(username))
        {
            lines.Add($"  username: '{EscapeYaml(username)}'");
            lines.Add($"  password: '{EscapeYaml(password ?? string.Empty)}'");
        }
        if (!string.IsNullOrWhiteSpace(mapDnsAddress))
        {
            lines.Add("mapdns:");
            lines.Add($"  address: {mapDnsAddress}");
            lines.Add("  port: 53");
            lines.Add("  network: 100.64.0.0");
            lines.Add("  netmask: 255.192.0.0");
            lines.Add("  cache-size: 4096");
        }
        lines.Add("misc:");
        lines.Add("  task-stack-size: 81920");

        File.WriteAllLines(path, lines);
        return path;
    }

    private static string EscapeYaml(string value) => value.Replace("'", "''", StringComparison.Ordinal);
}
