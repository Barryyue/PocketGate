using global::Android.Content;
using global::Android.Content.PM;
using global::Android.Net;
using global::Android.OS;
using NetworkControl.Core.Profiles;

namespace NetworkControl.Engine.Android;

/// <summary>
/// 監聽 Android 的默認網絡。只按類型（Wi-Fi / 行動網路 / 有線）切換不需要額外權限；
/// SSID 需要定位權限，沒有授權時 <see cref="NetworkContext.Ssid"/> 為 null。
/// </summary>
public sealed class AndroidNetworkMonitor : INetworkMonitor, IDisposable
{
    private readonly Context _context;
    private readonly ConnectivityManager _manager;
    private readonly NetworkCallback _callback;
    private NetworkContext _current;
    private int _disposed;

    public AndroidNetworkMonitor(Context context)
    {
        _context = context.ApplicationContext ?? context;
        _manager = (ConnectivityManager)_context.GetSystemService(Context.ConnectivityService)!;
        _current = Detect();
        _callback = new NetworkCallback(this);
        try
        {
            _manager.RegisterDefaultNetworkCallback(_callback);
        }
        catch (Exception ex) when (ex is Java.Lang.IllegalArgumentException or Java.Lang.SecurityException)
        {
            // 沒有 ACCESS_NETWORK_STATE 或系統限制：保持初始值，界面仍可使用手動切換
        }
    }

    public NetworkContext Current => Volatile.Read(ref _current);

    public event EventHandler? Changed;

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0)
        {
            try
            {
                _manager.UnregisterNetworkCallback(_callback);
            }
            catch (Java.Lang.IllegalArgumentException)
            {
                // 已經註銷
            }
        }
    }

    private void Refresh()
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

    private NetworkContext Detect()
    {
        var network = _manager.ActiveNetwork;
        var capabilities = network is null ? null : _manager.GetNetworkCapabilities(network);
        if (capabilities?.HasTransport(TransportType.Vpn) == true)
        {
            // VPN 自身不是「底層網絡」：找第一個非 VPN 的可用網絡
#pragma warning disable CA1422 // GetAllNetworks 在 API 31 起標為過時，但仍是列舉底層網絡的可用方法
            foreach (var candidate in _manager.GetAllNetworks())
#pragma warning restore CA1422
            {
                var candidateCapabilities = _manager.GetNetworkCapabilities(candidate);
                if (candidateCapabilities is not null && !candidateCapabilities.HasTransport(TransportType.Vpn))
                {
                    network = candidate;
                    capabilities = candidateCapabilities;
                    break;
                }
            }
        }

        if (network is null || capabilities is null)
        {
            return NetworkContext.None;
        }

        var kind = capabilities.HasTransport(TransportType.Wifi) ? NetworkKind.Wifi
            : capabilities.HasTransport(TransportType.Cellular) ? NetworkKind.Cellular
            : capabilities.HasTransport(TransportType.Ethernet) ? NetworkKind.Wired
            : NetworkKind.Other;
        var ssid = kind == NetworkKind.Wifi ? ReadSsid(capabilities) : null;
        var name = ssid ?? kind switch
        {
            NetworkKind.Wifi => "Wi-Fi",
            NetworkKind.Cellular => "Mobile",
            NetworkKind.Wired => "Ethernet",
            _ => "Network",
        };

        return new NetworkContext(kind, ssid, network.ToString(), name);
    }

    private string? ReadSsid(NetworkCapabilities capabilities)
    {
        if (Build.VERSION.SdkInt < BuildVersionCodes.S)
        {
            return null;
        }

        if (_context.CheckSelfPermission(global::Android.Manifest.Permission.AccessFineLocation) != Permission.Granted)
        {
            return null;
        }

        try
        {
            if (capabilities.TransportInfo is global::Android.Net.Wifi.WifiInfo wifi && wifi.SSID is { Length: > 0 } ssid)
            {
                return ssid.Trim('"');
            }
        }
        catch (Java.Lang.SecurityException)
        {
            // 定位權限被撤銷
        }

        return null;
    }

    private sealed class NetworkCallback(AndroidNetworkMonitor owner) : ConnectivityManager.NetworkCallback
    {
        public override void OnAvailable(Network network) => owner.Refresh();

        public override void OnLost(Network network) => owner.Refresh();

        public override void OnCapabilitiesChanged(Network network, NetworkCapabilities capabilities) => owner.Refresh();
    }
}
