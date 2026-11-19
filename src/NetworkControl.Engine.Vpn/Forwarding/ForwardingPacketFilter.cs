using System.Collections.Concurrent;
using System.Net;

namespace NetworkControl.Engine.Vpn.Forwarding;

public sealed record ActiveForwardingConnection(
    string Key,
    int? Uid,
    string PackageName,
    string Destination,
    int RemotePort,
    string Protocol,
    long OpenedAtMs,
    bool PendingCut);

/// <summary>
/// 逐連線轉發封包狀態與活躍連線追蹤器（K-112 / K-162）：
/// 追蹤轉發中的 TCP 連線，支援規則變更失效重判與使用者手動一鍵雙向 RST 切斷。
/// </summary>
public sealed class ForwardingConnectionTracker
{
    private readonly ConcurrentDictionary<string, TrackedEntry> _entries = new(StringComparer.Ordinal);
    private readonly long _maxAgeMs;

    private sealed class TrackedEntry
    {
        public required ActiveForwardingConnection Info { get; set; }
        public volatile bool CutRequested;
    }

    public ForwardingConnectionTracker(long maxAgeMs = 6L * 60 * 60 * 1000)
    {
        _maxAgeMs = maxAgeMs;
    }

    public void Record(string key, int? uid, string packageName, string destination, int remotePort, string protocol, long nowMs)
    {
        _entries[key] = new TrackedEntry
        {
            Info = new ActiveForwardingConnection(key, uid, packageName, destination, remotePort, protocol, nowMs, false),
        };
    }

    public bool RequestCut(string key)
    {
        if (_entries.TryGetValue(key, out var entry))
        {
            entry.CutRequested = true;
            entry.Info = entry.Info with { PendingCut = true };
            return true;
        }

        return false;
    }

    public bool ConsumeCutIfRequested(string key)
    {
        if (_entries.TryGetValue(key, out var entry) && entry.CutRequested)
        {
            _entries.TryRemove(key, out _);
            return true;
        }

        return false;
    }

    public IReadOnlyList<ActiveForwardingConnection> Snapshot(long nowMs)
    {
        var cutoff = nowMs - _maxAgeMs;
        var list = new List<ActiveForwardingConnection>();
        foreach (var (key, entry) in _entries)
        {
            if (entry.Info.OpenedAtMs < cutoff)
            {
                _entries.TryRemove(key, out _);
                continue;
            }

            list.Add(entry.Info);
        }

        return list.OrderByDescending(c => c.OpenedAtMs).ToList();
    }

    public void Reset() => _entries.Clear();
}
