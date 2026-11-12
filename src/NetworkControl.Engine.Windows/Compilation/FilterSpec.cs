using System.Globalization;
using System.Net;
using System.Text;
using NetworkControl.Core.Domain;

namespace NetworkControl.Engine.Windows.Compilation;

internal enum IpFamily : byte
{
    V4 = 4,
    V6 = 6,
}

internal enum FilterRole : byte
{
    /// <summary>內置必要放行：回環、自身、DHCP、系統 DNS、NTP、ICMP。</summary>
    Builtin = 0,

    /// <summary>「全部封鎖」模式的總阻斷。</summary>
    BlockAll = 1,

    /// <summary>由用戶規則編譯而來。</summary>
    Rule = 2,

    /// <summary>詢問 / 白名單模式的默認阻斷（權重最低）；它攔下的連接會觸發詢問。</summary>
    DefaultBlock = 3,
}

/// <summary>
/// 一個 WFP 過濾器的平台無關描述。規則編譯器輸出它，下發層把它轉成 FWPM_FILTER0。
/// <see cref="Key"/> 包含所有影響行為的字段，用於與已下發的過濾器做差異比較。
/// </summary>
internal sealed record FilterSpec
{
    public required string Name { get; init; }

    public required IpFamily Family { get; init; }

    public required bool Permit { get; init; }

    /// <summary>精確的 64 位權重：值越大越先被評估。</summary>
    public required ulong Weight { get; init; }

    public required FilterRole Role { get; init; }

    public Guid? RuleId { get; init; }

    /// <summary>應用的 DOS 路徑；為 null 表示任意應用。</summary>
    public string? AppPath { get; init; }

    /// <summary>遠端網段（OR 關係）；為空表示任意地址。</summary>
    public IReadOnlyList<IPNetwork> RemoteNetworks { get; init; } = [];

    public PortRange? RemotePort { get; init; }

    public PortRange? LocalPort { get; init; }

    public L4Protocol? Protocol { get; init; }

    public bool LoopbackOnly { get; init; }

    public string Key
    {
        get
        {
            var sb = new StringBuilder();
            sb.Append(Family).Append('|').Append(Permit ? 'P' : 'B').Append('|').Append(Weight.ToString(CultureInfo.InvariantCulture))
                .Append('|').Append(AppPath?.ToUpperInvariant())
                .Append('|').Append(string.Join(',', RemoteNetworks.Select(n => n.ToString())))
                .Append('|').Append(RemotePort?.ToString())
                .Append('|').Append(LocalPort?.ToString())
                .Append('|').Append(Protocol?.ToString())
                .Append('|').Append(LoopbackOnly ? 'L' : '-');
            return sb.ToString();
        }
    }
}
