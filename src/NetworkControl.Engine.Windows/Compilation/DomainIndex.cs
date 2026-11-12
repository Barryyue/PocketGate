using System.Collections.Concurrent;
using System.Net;
using NetworkControl.Core.Matching;

namespace NetworkControl.Engine.Windows.Compilation;

/// <summary>
/// 域名 → 最近解析出的 IP。WFP 只能按 IP 過濾，域名規則在編譯時通過它展開成 IP 過濾器（設計文檔 §10.4）。
/// 與核心的 DnsMapper（IP → 域名，用於判決與展示）方向相反，因此在引擎內單獨維護。
/// </summary>
internal sealed class DomainIndex(TimeProvider time)
{
    /// <summary>ETW 事件不帶 TTL：IP 在最後一次被解析後保留這麼久。</summary>
    public static readonly TimeSpan Retention = TimeSpan.FromMinutes(15);

    private readonly ConcurrentDictionary<string, ConcurrentDictionary<IPAddress, DateTimeOffset>> _byName = new(StringComparer.Ordinal);

    /// <summary>記錄一次解析；返回是否出現了新的 IP（需要重新編譯涉及該域名的規則）。</summary>
    public bool Observe(string name, IEnumerable<IPAddress> addresses)
    {
        var normalized = DomainName.Normalize(name);
        if (normalized.Length == 0)
        {
            return false;
        }

        var expires = time.GetUtcNow() + Retention;
        var entries = _byName.GetOrAdd(normalized, _ => new ConcurrentDictionary<IPAddress, DateTimeOffset>());
        var added = false;
        foreach (var address in addresses)
        {
            var key = address.IsIPv4MappedToIPv6 ? address.MapToIPv4() : address;
            added |= entries.TryAdd(key, expires);
            entries[key] = expires;
        }

        return added;
    }

    /// <summary>某個域名是否匹配給定模式中的任意一個。</summary>
    public static bool MatchesAny(string name, IEnumerable<DomainPattern> patterns)
    {
        var normalized = DomainName.Normalize(name);
        return patterns.Any(p => p.Matches(normalized));
    }

    /// <summary>取得匹配模式的所有未過期 IP。</summary>
    public IReadOnlyList<IPAddress> Resolve(DomainPattern pattern)
    {
        var now = time.GetUtcNow();
        var result = new HashSet<IPAddress>();
        foreach (var (name, entries) in _byName)
        {
            if (!pattern.Matches(name))
            {
                continue;
            }

            foreach (var (address, expires) in entries)
            {
                if (expires > now)
                {
                    result.Add(address);
                }
            }
        }

        return result.ToArray();
    }

    /// <summary>移除過期條目；返回是否有條目被移除。</summary>
    public bool Prune()
    {
        var now = time.GetUtcNow();
        var removed = false;
        foreach (var (name, entries) in _byName)
        {
            foreach (var (address, expires) in entries)
            {
                if (expires <= now)
                {
                    removed |= entries.TryRemove(address, out _);
                }
            }

            if (entries.IsEmpty)
            {
                _byName.TryRemove(name, out _);
            }
        }

        return removed;
    }
}
