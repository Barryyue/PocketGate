using System.Net.NetworkInformation;
using NetworkControl.Core.Profiles;

namespace NetworkControl.Engine.Windows.Platform;

/// <summary>
/// 監聽 Windows 的主要網絡。使用 <see cref="NetworkInterface"/> 判斷有線 / Wi-Fi / 蜂窩；
/// SSID 需要 WlanAPI，目前在 Windows 上留空（配置文件按 SSID 匹配暫不可用，按類型和網絡 ID 可用）。
/// </summary>
public sealed class WindowsNetworkMonitor : INetworkMonitor, IDisposable
{
    private NetworkContext _current = Detect();
    private int _disposed;

    public WindowsNetworkMonitor()
    {
        NetworkChange.NetworkAddressChanged += OnNetworkAddressChanged;
    }

    public NetworkContext Current => Volatile.Read(ref _current);

    public event EventHandler? Changed;

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0)
        {
            NetworkChange.NetworkAddressChanged -= OnNetworkAddressChanged;
        }
    }

    private void OnNetworkAddressChanged(object? sender, EventArgs e)
    {
        var next = Detect();
        var previous = Volatile.Read(ref _current);
        if (previous.Fingerprint == next.Fingerprint)
        {
            return;
        }

        Volatile.Write(ref _current, next);
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private static NetworkContext Detect()
    {
        var interfaces = NetworkInterface.GetAllNetworkInterfaces()
            .Where(i => i.OperationalStatus == OperationalStatus.Up
                        && i.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel))
            .ToArray();
        if (interfaces.Length == 0)
        {
            return NetworkContext.None;
        }

        var selected = interfaces.FirstOrDefault(i => i.NetworkInterfaceType == NetworkInterfaceType.Wireless80211)
            ?? interfaces.FirstOrDefault(i => i.NetworkInterfaceType is NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet)
            ?? interfaces.FirstOrDefault(i => i.NetworkInterfaceType is NetworkInterfaceType.Wman or NetworkInterfaceType.Wwanpp)
            ?? interfaces[0];

        var kind = selected.NetworkInterfaceType switch
        {
            NetworkInterfaceType.Wireless80211 => NetworkKind.Wifi,
            NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet => NetworkKind.Wired,
            NetworkInterfaceType.Wman or NetworkInterfaceType.Wwanpp => NetworkKind.Cellular,
            _ => NetworkKind.Other,
        };

        return new NetworkContext(kind, null, selected.Id, selected.Name);
    }
}
