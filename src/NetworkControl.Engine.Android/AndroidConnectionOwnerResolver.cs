using System.Net;
using global::Android.Content;
using global::Android.Net;
using global::Android.Systems;
using Java.Net;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Domain;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Android;

/// <summary>
/// 用 ConnectivityManager.getConnectionOwnerUid（API 29+）查詢報文所屬的 UID。只有當前生效的 VPN 應用可以調用。
/// </summary>
public sealed partial class AndroidConnectionOwnerResolver(Context context, ILogger<AndroidConnectionOwnerResolver> logger) : IConnectionOwnerResolver
{
    private const int InvalidUid = -1;

    private readonly ConnectivityManager _connectivity =
        (ConnectivityManager?)context.GetSystemService(Context.ConnectivityService) ?? throw new InvalidOperationException("ConnectivityManager 不可用。");

    private int _securityFailures;

    public int? GetOwnerUid(L4Protocol protocol, IPEndPoint local, IPEndPoint remote)
    {
        int proto;
        switch (protocol)
        {
            case L4Protocol.Tcp:
                proto = OsConstants.IpprotoTcp;
                break;
            case L4Protocol.Udp:
                proto = OsConstants.IpprotoUdp;
                break;
            default:
                return null;
        }

        try
        {
            using var localAddress = ToJava(local);
            using var remoteAddress = ToJava(remote);
            var uid = _connectivity.GetConnectionOwnerUid(proto, localAddress, remoteAddress);
            return uid == InvalidUid ? null : uid;
        }
        catch (Java.Lang.SecurityException ex)
        {
            // 不是當前生效的 VPN（例如虛擬網卡剛被撤銷）；只記錄前幾次，避免刷屏
            if (Interlocked.Increment(ref _securityFailures) <= 3)
            {
                LogSecurityFailure(logger, ex);
            }

            return null;
        }
        catch (Java.Lang.IllegalArgumentException)
        {
            return null;
        }
    }

    private static InetSocketAddress ToJava(IPEndPoint endpoint) =>
        new(InetAddress.GetByAddress(endpoint.Address.GetAddressBytes()), endpoint.Port);

    [LoggerMessage(Level = LogLevel.Warning, Message = "查詢連接所屬 UID 被拒絕")]
    private static partial void LogSecurityFailure(ILogger logger, Exception exception);
}
