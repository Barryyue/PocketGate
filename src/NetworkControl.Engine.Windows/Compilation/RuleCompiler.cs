using System.Net;
using System.Net.Sockets;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;

namespace NetworkControl.Engine.Windows.Compilation;

/// <summary>規則編譯的輸入。</summary>
internal sealed record CompileInput
{
    /// <summary>按優先級排序的生效規則（RuleSet.Rules）。</summary>
    public required IReadOnlyList<Rule> Rules { get; init; }

    public required FilterMode Mode { get; init; }

    public bool AllowIcmp { get; init; } = true;

    /// <summary>沒有入站規則命中時默認阻斷。</summary>
    public bool InboundDefaultDeny { get; init; }

    /// <summary>服務自身的可執行文件，總是放行。</summary>
    public string? SelfPath { get; init; }

    /// <summary>系統 DNS / NTP 客戶端宿主（svchost.exe）。</summary>
    public string? SystemServiceHostPath { get; init; }

    /// <summary>域名模式 → 當前已知 IP。</summary>
    public required Func<DomainPattern, IReadOnlyList<IPAddress>> ResolveDomain { get; init; }

    /// <summary>帶通配符的路徑模式 → 磁盤上實際存在的路徑。</summary>
    public required Func<string, IReadOnlyList<string>> ExpandPath { get; init; }
}

/// <summary>一條無法編譯為 WFP 過濾器的規則及原因（記錄日誌，在界面上提示）。</summary>
internal sealed record SkippedRule(Guid RuleId, string Reason);

internal sealed record CompileResult(IReadOnlyList<FilterSpec> Filters, IReadOnlyList<SkippedRule> Skipped);

/// <summary>
/// 把生效規則集編譯成 WFP 過濾器（設計文檔 §10.2–10.3）。純函數，可完整單元測試。
/// <para>
/// 權重布局（同一子層內，權重越大越先評估，第一個命中的終止性過濾器決定結果）：
/// 內置放行 &gt; 全部封鎖 &gt; 用戶規則（按優先級遞減，與 RuleSet 的排序一一對應）&gt; 默認阻斷。
/// 使用 FWP_UINT64 精確權重，因此不需要把優先級壓縮到 16 個權重檔。
/// </para>
/// </summary>
internal static class RuleCompiler
{
    public const ulong BuiltinWeightBase = 0xF000_0000_0000_0000;
    public const ulong BlockAllWeight = 0xE000_0000_0000_0000;
    public const ulong RuleWeightBase = 0x8000_0000_0000_0000;
    public const ulong DefaultBlockWeight = 1;

    /// <summary>每條規則可展開為多個過濾器（多個路徑、兩個地址族），預留的權重間隔。</summary>
    private const ulong WeightsPerRule = 1024;

    private static readonly IpFamily[] BothFamilies = [IpFamily.V4, IpFamily.V6];

    public static CompileResult Compile(CompileInput input)
    {
        var filters = new List<FilterSpec>();
        var skipped = new List<SkippedRule>();

        AddBuiltins(input, filters);

        switch (input.Mode)
        {
            case FilterMode.BlockAll:
                foreach (var family in BothFamilies)
                {
                    filters.Add(new FilterSpec { Name = "Block all outbound", Family = family, Permit = false, Weight = BlockAllWeight, Role = FilterRole.BlockAll });
                    filters.Add(new FilterSpec { Name = "Block all inbound", Family = family, Permit = false, Weight = BlockAllWeight, Role = FilterRole.BlockAll, Inbound = true });
                }

                return new CompileResult(filters, skipped);

            case FilterMode.Disabled:
                // 停用過濾：只保留內置放行（實際上等於全部放行）
                return new CompileResult(filters, skipped);
        }

        for (var rank = 0; rank < input.Rules.Count; rank++)
        {
            var rule = input.Rules[rank];
            var weight = RuleWeightBase - ((ulong)rank * WeightsPerRule);
            CompileRule(rule, weight, input, filters, skipped);
        }

        if (input.Mode is FilterMode.Ask or FilterMode.Whitelist)
        {
            foreach (var family in BothFamilies)
            {
                filters.Add(new FilterSpec { Name = "Default block", Family = family, Permit = false, Weight = DefaultBlockWeight, Role = FilterRole.DefaultBlock });
            }
        }

        if (input.InboundDefaultDeny)
        {
            foreach (var family in BothFamilies)
            {
                filters.Add(new FilterSpec { Name = "Default inbound block", Family = family, Permit = false, Weight = DefaultBlockWeight, Role = FilterRole.InboundDefaultBlock, Inbound = true });
            }
        }

        return new CompileResult(filters, skipped);
    }

    private static void AddBuiltins(CompileInput input, List<FilterSpec> filters)
    {
        var weight = BuiltinWeightBase;
        void Add(string name, IpFamily family, Func<FilterSpec, FilterSpec>? configure = null, bool inbound = false)
        {
            var spec = new FilterSpec { Name = name, Family = family, Permit = true, Weight = weight--, Role = FilterRole.Builtin, Inbound = inbound };
            filters.Add(configure is null ? spec : configure(spec));
        }

        foreach (var family in BothFamilies)
        {
            Add("Builtin: loopback", family, s => s with { LoopbackOnly = true });
            if (input.SelfPath is { } self)
            {
                Add("Builtin: NetworkControl service", family, s => s with { AppPath = self });
            }

            if (input.SystemServiceHostPath is { } svchost)
            {
                Add("Builtin: system DNS client", family, s => s with { AppPath = svchost, RemotePort = PortRange.Single(53) });
                Add("Builtin: system time (NTP)", family, s => s with { AppPath = svchost, RemotePort = PortRange.Single(123), Protocol = L4Protocol.Udp });
            }
        }

        Add("Builtin: DHCP", IpFamily.V4, s => s with { RemotePort = new PortRange(67, 68), Protocol = L4Protocol.Udp });
        Add("Builtin: DHCPv6", IpFamily.V6, s => s with { RemotePort = new PortRange(546, 547), Protocol = L4Protocol.Udp });
        Add("Builtin: ICMPv6 (neighbor discovery)", IpFamily.V6, s => s with { Protocol = L4Protocol.IcmpV6 });
        if (input.AllowIcmp)
        {
            Add("Builtin: ICMP", IpFamily.V4, s => s with { Protocol = L4Protocol.Icmp });
        }

        // 入站：只放行回環、DHCP 客戶端和 ICMP
        foreach (var family in BothFamilies)
        {
            Add("Builtin inbound: loopback", family, s => s with { LoopbackOnly = true }, inbound: true);
        }

        Add("Builtin inbound: DHCP", IpFamily.V4, s => s with { LocalPort = new PortRange(68, 68), Protocol = L4Protocol.Udp }, inbound: true);
        Add("Builtin inbound: DHCPv6", IpFamily.V6, s => s with { LocalPort = new PortRange(546, 546), Protocol = L4Protocol.Udp }, inbound: true);
        Add("Builtin inbound: ICMPv6 (neighbor discovery)", IpFamily.V6, s => s with { Protocol = L4Protocol.IcmpV6 }, inbound: true);
        if (input.AllowIcmp)
        {
            Add("Builtin inbound: ICMP", IpFamily.V4, s => s with { Protocol = L4Protocol.Icmp }, inbound: true);
        }
    }

    private static void CompileRule(Rule rule, ulong weight, CompileInput input, List<FilterSpec> filters, List<SkippedRule> skipped)
    {
        var c = rule.Conditions;

        if (rule.App is { ServiceName: not null } or { PackageId: not null })
        {
            skipped.Add(new SkippedRule(rule.Id, "service / package matching is not supported by the user-mode engine"));
            return;
        }

        // 應用：精確路徑直接使用；通配路徑展開為磁盤上實際存在的文件
        IReadOnlyList<string?> apps;
        if (rule.App is null)
        {
            apps = [null];
        }
        else if (rule.App.HasWildcard)
        {
            apps = input.ExpandPath(rule.App.PathPattern);
            if (apps.Count == 0)
            {
                skipped.Add(new SkippedRule(rule.Id, "no file matches the path pattern"));
                return;
            }
        }
        else
        {
            apps = [rule.App.PathPattern];
        }

        // 遠端：按地址族拆分網段；為 null 表示任意地址
        Dictionary<IpFamily, IReadOnlyList<IPNetwork>?> remotes;
        switch (c.Remote)
        {
            case null:
                remotes = new() { [IpFamily.V4] = null, [IpFamily.V6] = null };
                break;
            case AddressTarget a:
                remotes = SplitByFamily(a.Networks);
                break;
            case ZoneTarget { Zone: NetworkZone.Loopback }:
                // 回環已經被內置過濾器放行，阻斷回環的規則在 WFP 層不生效
                skipped.Add(new SkippedRule(rule.Id, "loopback traffic is always allowed"));
                return;
            case ZoneTarget { Zone: NetworkZone.LocalNetwork }:
                remotes = SplitByFamily(NetworkZones.LocalNetworkRanges);
                break;
            case ZoneTarget { Zone: NetworkZone.Internet }:
                remotes = new()
                {
                    [IpFamily.V4] = IpRangeMath.Complement([.. NetworkZones.LocalNetworkRanges, IPNetwork.Parse("127.0.0.0/8")], AddressFamily.InterNetwork),
                    [IpFamily.V6] = IpRangeMath.Complement([.. NetworkZones.LocalNetworkRanges, IPNetwork.Parse("::1/128")], AddressFamily.InterNetworkV6),
                };
                break;
            case DomainTarget d:
                var addresses = input.ResolveDomain(DomainPattern.Parse(d.Pattern));
                if (addresses.Count == 0)
                {
                    // 還沒有觀測到解析結果：等 DNS 事件到達後重新編譯
                    skipped.Add(new SkippedRule(rule.Id, "domain has not been resolved yet"));
                    return;
                }

                remotes = SplitByFamily(addresses.Select(ip => new IPNetwork(ip, ip.AddressFamily == AddressFamily.InterNetwork ? 32 : 128)));
                break;
            default:
                skipped.Add(new SkippedRule(rule.Id, $"unsupported remote target {c.Remote.GetType().Name}"));
                return;
        }

        foreach (var inbound in Directions(c.Direction))
        {
            foreach (var app in apps)
            {
                foreach (var (family, networks) in remotes)
                {
                    if (networks is { Count: 0 })
                    {
                        continue;
                    }

                    filters.Add(new FilterSpec
                    {
                        Name = $"Rule {rule.Id:N}",
                        Family = family,
                        Permit = rule.Action == VerdictAction.Allow,
                        Weight = weight,
                        Role = FilterRole.Rule,
                        RuleId = rule.Id,
                        AppPath = app,
                        RemoteNetworks = networks ?? [],
                        RemotePort = c.RemotePort,
                        LocalPort = c.LocalPort,
                        Protocol = NormalizeProtocol(c.Protocol, family),
                        Inbound = inbound,
                    });
                }
            }
        }
    }

    private static IEnumerable<bool> Directions(Direction? direction) => direction switch
    {
        Direction.Outbound => [false],
        Direction.Inbound => [true],
        _ => [false, true],
    };

    private static L4Protocol? NormalizeProtocol(L4Protocol? protocol, IpFamily family) => protocol switch
    {
        L4Protocol.Icmp when family == IpFamily.V6 => L4Protocol.IcmpV6,
        L4Protocol.IcmpV6 when family == IpFamily.V4 => L4Protocol.Icmp,
        _ => protocol,
    };

    private static Dictionary<IpFamily, IReadOnlyList<IPNetwork>?> SplitByFamily(IEnumerable<IPNetwork> networks)
    {
        var list = networks.ToList();
        return new()
        {
            [IpFamily.V4] = list.Where(n => n.BaseAddress.AddressFamily == AddressFamily.InterNetwork).ToArray(),
            [IpFamily.V6] = list.Where(n => n.BaseAddress.AddressFamily == AddressFamily.InterNetworkV6).ToArray(),
        };
    }
}
