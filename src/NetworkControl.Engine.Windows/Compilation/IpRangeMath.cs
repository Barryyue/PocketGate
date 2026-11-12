using System.Net;
using System.Net.Sockets;

namespace NetworkControl.Engine.Windows.Compilation;

/// <summary>IP 網段運算。WFP 條件只能表達「屬於某些網段」，「不屬於某些網段」需要先求補集。</summary>
internal static class IpRangeMath
{
    /// <summary>
    /// 求整個地址空間中，不與 <paramref name="excluded"/> 中任何網段重疊的最小 CIDR 集合。
    /// </summary>
    public static IReadOnlyList<IPNetwork> Complement(IEnumerable<IPNetwork> excluded, AddressFamily family)
    {
        var bits = family == AddressFamily.InterNetwork ? 32 : 128;
        var ranges = excluded
            .Where(n => n.BaseAddress.AddressFamily == family)
            .Select(n => ToRange(n, bits))
            .ToList();

        var result = new List<IPNetwork>();
        Cover(UInt128.Zero, 0, bits, ranges, result);
        return result;
    }

    private static void Cover(UInt128 prefix, int length, int bits, List<(UInt128 Start, UInt128 End)> excluded, List<IPNetwork> result)
    {
        var (start, end) = Range(prefix, length, bits);
        var overlaps = false;
        foreach (var (s, e) in excluded)
        {
            if (s <= start && end <= e)
            {
                // 整個前綴都被排除
                return;
            }

            if (s <= end && start <= e)
            {
                overlaps = true;
            }
        }

        if (!overlaps)
        {
            result.Add(new IPNetwork(ToAddress(prefix, bits), length));
            return;
        }

        // 部分重疊：二分繼續
        var half = UInt128.One << (bits - length - 1);
        Cover(prefix, length + 1, bits, excluded, result);
        Cover(prefix + half, length + 1, bits, excluded, result);
    }

    private static (UInt128 Start, UInt128 End) Range(UInt128 prefix, int length, int bits)
    {
        var size = length == 0 ? (bits == 128 ? UInt128.MaxValue : (UInt128.One << bits) - 1) : (UInt128.One << (bits - length)) - 1;
        return (prefix, prefix + size);
    }

    private static (UInt128 Start, UInt128 End) ToRange(IPNetwork network, int bits)
    {
        var value = ToInteger(network.BaseAddress, bits);
        return Range(value, network.PrefixLength, bits);
    }

    public static UInt128 ToInteger(IPAddress address, int bits)
    {
        var bytes = address.GetAddressBytes();
        UInt128 value = 0;
        foreach (var b in bytes)
        {
            value = (value << 8) | b;
        }

        return bits == bytes.Length * 8 ? value : throw new ArgumentException("Address family mismatch.", nameof(address));
    }

    /// <summary>字節數組長度（4 或 16）決定地址族。</summary>
    private static IPAddress ToAddress(UInt128 value, int bits)
    {
        var length = bits / 8;
        var bytes = new byte[length];
        for (var i = length - 1; i >= 0; i--)
        {
            bytes[i] = (byte)(value & 0xFF);
            value >>= 8;
        }

        return new IPAddress(bytes);
    }
}
