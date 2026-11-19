using System.Net;

namespace NetworkControl.Engine.Vpn.Blocklists;

/// <summary>64 位元 FNV-1a 域名雜湊。</summary>
public static class DomainHash
{
    private const ulong OffsetBasis = 0xcbf29ce484222325UL;
    private const ulong Prime = 0x100000001b3UL;

    public static ulong Hash(ReadOnlySpan<char> domain)
    {
        var h = OffsetBasis;
        foreach (var ch in domain)
        {
            var c = ch is >= 'A' and <= 'Z' ? (char)(ch + 32) : ch;
            h ^= (byte)(c & 0xff);
            h *= Prime;
            if (c > 0xff)
            {
                h ^= (byte)(c >> 8);
                h *= Prime;
            }
        }

        return h;
    }

    public static string? NormalizeDomain(string raw)
    {
        var s = raw.Trim().TrimEnd('.');
        if (s.Length == 0 || s.Length > 253 || !s.Contains('.', StringComparison.Ordinal)) return null;
        if (IPAddress.TryParse(s, out _)) return null;
        foreach (var ch in s)
        {
            if (ch is not ((>= 'a' and <= 'z') or (>= 'A' and <= 'Z') or (>= '0' and <= '9') or '.' or '-' or '_'))
            {
                return null;
            }
        }

        return s.ToLowerInvariant();
    }
}

/// <summary>
/// 高效域名攔截列表：以排序 64 位元雜湊陣列儲存（每域名 8 bytes，零 GC 壓力），支援精確、子域名與例外規則。
/// </summary>
public sealed class DomainBlocklist
{
    private readonly ulong[] _exact;
    private readonly ulong[] _subdomains;
    private readonly ulong[] _allowExact;
    private readonly ulong[] _allowSubdomains;

    public int Size => _exact.Length + _subdomains.Length;

    public DomainBlocklist(ulong[] exact, ulong[] subdomains, ulong[] allowExact, ulong[] allowSubdomains)
    {
        _exact = exact;
        _subdomains = subdomains;
        _allowExact = allowExact;
        _allowSubdomains = allowSubdomains;
    }

    public bool Matches(string domain)
    {
        var norm = DomainHash.NormalizeDomain(domain);
        if (norm is null) return false;

        if (Contains(_allowExact, DomainHash.Hash(norm)) || MatchesSuffix(_allowSubdomains, norm))
        {
            return false;
        }

        return Contains(_exact, DomainHash.Hash(norm)) || MatchesSuffix(_subdomains, norm);
    }

    private static bool MatchesSuffix(ulong[] sorted, string domain)
    {
        if (sorted.Length == 0) return false;
        var span = domain.AsSpan();
        while (true)
        {
            if (Contains(sorted, DomainHash.Hash(span))) return true;
            var dot = span.IndexOf('.');
            if (dot < 0 || dot + 1 >= span.Length) break;
            span = span[(dot + 1)..];
        }

        return false;
    }

    private static bool Contains(ulong[] sorted, ulong target) =>
        sorted.Length > 0 && Array.BinarySearch(sorted, target) >= 0;
}

public sealed record BlocklistParseResult(DomainBlocklist List, int Skipped);

/// <summary>支援 hosts、純域名、AdBlock / AdGuard DNS 語法（||d^、*.d、@@||d^）。</summary>
public static class BlocklistParser
{
    public static BlocklistParseResult Parse(TextReader reader)
    {
        var exact = new HashSet<ulong>();
        var subdomains = new HashSet<ulong>();
        var allowExact = new HashSet<ulong>();
        var allowSubdomains = new HashSet<ulong>();
        var skipped = 0;

        string? raw;
        while ((raw = reader.ReadLine()) is not null)
        {
            var line = raw.Trim();
            if (line.Length == 0 || line[0] is '#' or '!') continue;
            var hashIdx = line.IndexOf('#');
            if (hashIdx > 0) line = line[..hashIdx].Trim();
            if (line.Length == 0) continue;

            if (line.StartsWith('/') && line.EndsWith('/'))
            {
                skipped++;
                continue;
            }

            if (line.StartsWith("@@", StringComparison.Ordinal))
            {
                if (!TryParseAdblock(line[2..], allowSubdomains)) skipped++;
                continue;
            }

            if (line.StartsWith("||", StringComparison.Ordinal))
            {
                if (!TryParseAdblock(line, subdomains)) skipped++;
                continue;
            }

            if (line.StartsWith("*.", StringComparison.Ordinal))
            {
                var d = DomainHash.NormalizeDomain(line[2..]);
                if (d is not null) subdomains.Add(DomainHash.Hash(d));
                else skipped++;
                continue;
            }

            var parts = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            var candidate = parts.Length >= 2 && IPAddress.TryParse(parts[0], out _) ? parts[1] : parts[0];
            if (candidate is "localhost" or "localhost.localdomain" or "broadcasthost")
            {
                continue;
            }

            var norm = DomainHash.NormalizeDomain(candidate);
            if (norm is not null) exact.Add(DomainHash.Hash(norm));
            else skipped++;
        }

        return new BlocklistParseResult(
            new DomainBlocklist(ToSorted(exact), ToSorted(subdomains), ToSorted(allowExact), ToSorted(allowSubdomains)),
            skipped);
    }

    private static bool TryParseAdblock(string body, HashSet<ulong> target)
    {
        var s = body.StartsWith("||", StringComparison.Ordinal) ? body[2..] : body;
        var caret = s.IndexOf('^');
        if (caret < 0) return false;
        var domainPart = s[..caret];
        var rest = s[(caret + 1)..];
        if (domainPart.Contains('/', StringComparison.Ordinal) || domainPart.Contains('*', StringComparison.Ordinal))
        {
            return false;
        }

        if (rest.Contains("dnsrewrite=", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        var norm = DomainHash.NormalizeDomain(domainPart);
        if (norm is null) return false;
        target.Add(DomainHash.Hash(norm));
        return true;
    }

    private static ulong[] ToSorted(HashSet<ulong> set)
    {
        var arr = set.ToArray();
        Array.Sort(arr);
        return arr;
    }
}

public sealed record BuiltinBlocklist(string Id, string Name, IReadOnlyList<string> Urls, bool DefaultEnabled = false);

public static class BlocklistCatalog
{
    public static readonly IReadOnlyList<BuiltinBlocklist> Builtins =
    [
        new("anti-ad", "anti-AD (CN)", ["https://anti-ad.net/adguard.txt", "https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/anti-ad-adguard.txt"], DefaultEnabled: true),
        new("hagezi-multi", "HaGeZi Multi NORMAL", ["https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/multi.txt", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/multi.txt"], DefaultEnabled: true),
        new("hagezi-pro", "HaGeZi Multi PRO", ["https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt"]),
        new("hagezi-tif", "HaGeZi Threat Intelligence", ["https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/tif.mini.txt"]),
        new("oisd-small", "OISD Small", ["https://small.oisd.nl/"]),
        new("adguard-dns", "AdGuard DNS filter", ["https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"]),
        new("stevenblack", "StevenBlack Unified", ["https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"]),
    ];

    public static bool IsEncryptedDnsDomain(string host)
    {
        var h = host.Trim().TrimEnd('.').ToLowerInvariant();
        return h is "dns.google" or "dns.quad9.net" or "cloudflare-dns.com" or "1dot1dot1dot1.cloudflare-dns.com"
            or "dns.alidns.com" or "doh.pub" or "doh.360.cn" or "dns.adguard-dns.com" or "dns.nextdns.io";
    }
}
