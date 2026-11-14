using global::Android.App;
using global::Android.App.Usage;
using global::Android.Content;
using global::Android.Net;
using Microsoft.Extensions.Logging;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Android;

/// <summary>
/// 用 NetworkStatsManager 讀取按 UID 的流量（Wi-Fi + 行動網路）。需要用戶在系統設定中授予「使用情況存取權」
/// （PACKAGE_USAGE_STATS 是特殊權限，只能在設定頁授予，見 <see cref="CreateSettingsIntent"/>）。
/// </summary>
public sealed partial class AndroidUidTrafficSource(Context context, ILogger<AndroidUidTrafficSource> logger) : IUidTrafficSource
{
    private readonly NetworkStatsManager? _stats = (NetworkStatsManager?)context.GetSystemService(Context.NetworkStatsService);
    private readonly AppOpsManager? _appOps = (AppOpsManager?)context.GetSystemService(Context.AppOpsService);

    public bool IsAvailable
    {
        get
        {
            if (_stats is null || _appOps is null)
            {
                return false;
            }

            // API 36 起標記過時（替代接口需要 AttributionSource），但仍然可用，且覆蓋最低支持的 API 29
#pragma warning disable CA1422
            var mode = _appOps.UnsafeCheckOpNoThrow(AppOpsManager.OpstrGetUsageStats, global::Android.OS.Process.MyUid(), context.PackageName ?? "");
#pragma warning restore CA1422
            return mode == AppOpsManagerMode.Allowed;
        }
    }

    /// <summary>打開系統的「使用情況存取權」設定頁。</summary>
    public static Intent CreateSettingsIntent(Context context) =>
        new Intent(global::Android.Provider.Settings.ActionUsageAccessSettings)
            .SetData(global::Android.Net.Uri.Parse("package:" + context.PackageName))
            .AddFlags(ActivityFlags.NewTask);

    public IReadOnlyList<UidTrafficBucket> Query(DateTimeOffset from, DateTimeOffset to)
    {
        if (!IsAvailable || _stats is null)
        {
            return [];
        }

        var result = new List<UidTrafficBucket>();
        // ConnectivityType 已標記過時，但 NetworkStatsManager 仍按它查詢
#pragma warning disable CA1422, CS0618
        ConnectivityType[] types = [ConnectivityType.Wifi, ConnectivityType.Mobile];
#pragma warning restore CA1422, CS0618
        foreach (var type in types)
        {
            try
            {
                using var stats = _stats.QueryDetails(type, null, from.ToUnixTimeMilliseconds(), to.ToUnixTimeMilliseconds());
                if (stats is null)
                {
                    continue;
                }

                using var bucket = new NetworkStats.Bucket();
                while (stats.HasNextBucket)
                {
                    if (!stats.GetNextBucket(bucket))
                    {
                        break;
                    }

                    result.Add(new UidTrafficBucket(
                        bucket.Uid,
                        DateTimeOffset.FromUnixTimeMilliseconds(bucket.StartTimeStamp),
                        DateTimeOffset.FromUnixTimeMilliseconds(bucket.EndTimeStamp),
                        bucket.RxBytes,
                        bucket.TxBytes));
                }
            }
            catch (Java.Lang.SecurityException ex)
            {
                LogDenied(logger, ex.Message);
                return [];
            }
            catch (Java.Lang.Exception ex)
            {
                // 例如設備沒有行動網路：跳過這種網絡類型
                LogQueryFailed(logger, type, ex.Message);
            }
        }

        return result;
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "沒有讀取流量統計的權限：{Message}")]
    private static partial void LogDenied(ILogger logger, string? message);

    [LoggerMessage(Level = LogLevel.Debug, Message = "查詢網絡類型 {Type} 的流量統計失敗：{Message}")]
    private static partial void LogQueryFailed(ILogger logger, ConnectivityType type, string? message);
}
