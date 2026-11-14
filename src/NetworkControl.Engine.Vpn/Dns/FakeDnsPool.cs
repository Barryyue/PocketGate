using System.Buffers.Binary;
using System.Diagnostics.CodeAnalysis;
using System.Net;
using System.Net.Sockets;

namespace NetworkControl.Engine.Vpn.Dns;

/// <summary>
/// 假 DNS 地址池：每個域名分配 198.18.0.0/15（RFC 2544 基準測試保留地址，不會出現在互聯網上）中的一個地址，
/// 並能從地址反查域名，讓詢問和日誌顯示應用想連接的域名。地址用完後循環覆蓋最早分配的。
/// </summary>
public sealed class FakeDnsPool
{
    /// <summary>198.18.0.0/15。</summary>
    public static readonly IPNetwork Range = new(IPAddress.Parse("198.18.0.0"), 15);

    private const int Size = 1 << 17;
    private static readonly uint BaseAddress = BinaryPrimitives.ReadUInt32BigEndian(Range.BaseAddress.GetAddressBytes());

    private readonly Lock _gate = new();
    private readonly Dictionary<string, int> _byName = new(StringComparer.Ordinal);
    private readonly string?[] _byIndex;
    private int _next = 1;

    public FakeDnsPool(int capacity = Size)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(capacity, 2);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(capacity, Size);
        _byIndex = new string?[capacity];
    }

    public static bool Contains(IPAddress address) => Range.Contains(address);

    /// <summary>為域名分配（或返回已分配的）地址。</summary>
    public IPAddress Allocate(string name)
    {
        ArgumentException.ThrowIfNullOrEmpty(name);
        lock (_gate)
        {
            if (!_byName.TryGetValue(name, out var index))
            {
                // 跳過 .0：網絡地址
                index = _next;
                _next = _next + 1 >= _byIndex.Length ? 1 : _next + 1;
                if (_byIndex[index] is { } previous)
                {
                    _byName.Remove(previous);
                }

                _byIndex[index] = name;
                _byName[name] = index;
            }

            return ToAddress(index);
        }
    }

    public bool TryGetName(IPAddress address, [NotNullWhen(true)] out string? name)
    {
        name = null;
        if (address.AddressFamily != AddressFamily.InterNetwork || !Contains(address))
        {
            return false;
        }

        var index = (int)(BinaryPrimitives.ReadUInt32BigEndian(address.GetAddressBytes()) - BaseAddress);
        if (index <= 0 || index >= _byIndex.Length)
        {
            return false;
        }

        lock (_gate)
        {
            name = _byIndex[index];
        }

        return name is not null;
    }

    private static IPAddress ToAddress(int index)
    {
        Span<byte> bytes = stackalloc byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(bytes, BaseAddress + (uint)index);
        return new IPAddress(bytes);
    }
}
