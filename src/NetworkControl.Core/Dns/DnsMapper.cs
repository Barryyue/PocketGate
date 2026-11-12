using System.Collections.Concurrent;
using System.Net;
using Microsoft.Extensions.Options;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;

namespace NetworkControl.Core.Dns;

/// <summary>引擎觀測到的一次 DNS 解析結果。</summary>
public sealed record DnsObservation(string Name, IReadOnlyList<IPAddress> Addresses, TimeSpan Ttl, ProcessRef? Process = null);

/// <summary>某個遠端 IP 的候選域名，按優先級排序（同進程最近解析的在前）。</summary>
public sealed class DomainCandidates
{
    public static readonly DomainCandidates None = new([]);

    public DomainCandidates(IReadOnlyList<string> names) => Names = names;

    /// <summary>已規範化的候選域名。</summary>
    public IReadOnlyList<string> Names { get; }

    public string? Primary => Names.Count > 0 ? Names[0] : null;

    public bool IsEmpty => Names.Count == 0;
}

public sealed class DnsMapperOptions
{
    public int MaxAddresses { get; set; } = 100_000;

    public TimeSpan MinTtl { get; set; } = TimeSpan.FromSeconds(60);

    public TimeSpan MaxTtl { get; set; } = TimeSpan.FromHours(24);
}

/// <summary>
/// 維護「IP → 最近解析出這個 IP 的域名」映射，用於域名規則匹配和界面展示。
/// </summary>
public sealed class DnsMapper(TimeProvider time, IOptions<DnsMapperOptions> options)
{
    private const int MaxNamesPerAddress = 8;

    private readonly ConcurrentDictionary<IPAddress, Entry[]> _map = new();
    private readonly DnsMapperOptions _options = options.Value;

    public int Count => _map.Count;

    public void Observe(DnsObservation observation)
    {
        var name = DomainName.Normalize(observation.Name);
        if (name.Length == 0 || observation.Addresses.Count == 0)
        {
            return;
        }

        var now = time.GetUtcNow();
        var ttl = TimeSpan.FromTicks(Math.Clamp(observation.Ttl.Ticks, _options.MinTtl.Ticks, _options.MaxTtl.Ticks));
        var entry = new Entry(name, now + ttl, now, observation.Process);

        foreach (var address in observation.Addresses)
        {
            var key = address.IsIPv4MappedToIPv6 ? address.MapToIPv4() : address;
            _map.AddOrUpdate(key, static (_, e) => [e], static (_, existing, e) => Merge(existing, e), entry);
        }

        if (_map.Count > _options.MaxAddresses)
        {
            Prune(now);
        }
    }

    public DomainCandidates Lookup(IPAddress remote, ProcessRef? process, string? engineHostname = null)
    {
        var key = remote.IsIPv4MappedToIPv6 ? remote.MapToIPv4() : remote;
        var now = time.GetUtcNow();
        var names = new List<string>(2);

        // 引擎直接給出的主機名最可信
        if (!string.IsNullOrWhiteSpace(engineHostname))
        {
            names.Add(DomainName.Normalize(engineHostname));
        }

        if (_map.TryGetValue(key, out var entries))
        {
            // 同一進程解析的優先，其次按解析時間倒序
            foreach (var e in entries
                         .Where(e => e.ExpiresAt > now)
                         .OrderByDescending(e => process is not null && e.Process == process)
                         .ThenByDescending(e => e.ObservedAt))
            {
                if (!names.Contains(e.Name, StringComparer.Ordinal))
                {
                    names.Add(e.Name);
                }
            }
        }

        return names.Count == 0 ? DomainCandidates.None : new DomainCandidates(names);
    }

    private static Entry[] Merge(Entry[] existing, Entry incoming)
    {
        var list = new List<Entry>(existing.Length + 1) { incoming };
        list.AddRange(existing.Where(e => !(e.Name == incoming.Name && e.Process == incoming.Process)));
        return list.Count > MaxNamesPerAddress ? list.GetRange(0, MaxNamesPerAddress).ToArray() : list.ToArray();
    }

    private void Prune(DateTimeOffset now)
    {
        foreach (var (address, entries) in _map)
        {
            if (entries.All(e => e.ExpiresAt <= now))
            {
                _map.TryRemove(address, out _);
            }
        }

        // 仍然超限：按最近觀測時間淘汰最舊的一半
        if (_map.Count > _options.MaxAddresses)
        {
            var victims = _map
                .OrderBy(kv => kv.Value.Max(e => e.ObservedAt))
                .Take(_map.Count / 2)
                .Select(kv => kv.Key)
                .ToList();
            foreach (var address in victims)
            {
                _map.TryRemove(address, out _);
            }
        }
    }

    private readonly record struct Entry(string Name, DateTimeOffset ExpiresAt, DateTimeOffset ObservedAt, ProcessRef? Process);
}
