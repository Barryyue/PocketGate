using System.Runtime.InteropServices;

namespace NetworkControl.Engine.Windows.Platform;

/// <summary>
/// NT 設備路徑 ↔ DOS 路徑。WFP 的 AppId 是小寫的設備路徑（\device\harddiskvolume3\program files\...），
/// 界面和規則使用 DOS 路徑（C:\Program Files\...）。
/// </summary>
internal sealed partial class DevicePathResolver
{
    private readonly Func<IReadOnlyDictionary<string, string>> _loadMappings;
    private IReadOnlyDictionary<string, string> _deviceToDrive;
    private DateTime _loadedAt;

    public DevicePathResolver()
        : this(LoadMappings)
    {
    }

    /// <summary>測試用：注入「設備名 → 盤符」映射。</summary>
    internal DevicePathResolver(Func<IReadOnlyDictionary<string, string>> loadMappings)
    {
        _loadMappings = loadMappings;
        _deviceToDrive = loadMappings();
        _loadedAt = DateTime.UtcNow;
    }

    /// <summary>把設備路徑轉成 DOS 路徑；無法轉換時原樣返回。</summary>
    public string ToDosPath(string devicePath)
    {
        // 插拔移動硬盤會改變映射：定期刷新
        if (DateTime.UtcNow - _loadedAt > TimeSpan.FromMinutes(5))
        {
            _deviceToDrive = _loadMappings();
            _loadedAt = DateTime.UtcNow;
        }

        foreach (var (device, drive) in _deviceToDrive)
        {
            if (devicePath.StartsWith(device, StringComparison.OrdinalIgnoreCase)
                && (devicePath.Length == device.Length || devicePath[device.Length] == '\\'))
            {
                return drive + devicePath[device.Length..];
            }
        }

        return devicePath;
    }

    private static IReadOnlyDictionary<string, string> LoadMappings()
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        Span<char> buffer = stackalloc char[1024];
        for (var letter = 'A'; letter <= 'Z'; letter++)
        {
            var drive = $"{letter}:";
            var length = QueryDosDevice(drive, ref MemoryMarshal.GetReference(buffer), (uint)buffer.Length);
            if (length > 0)
            {
                // 返回值是以 NUL 分隔的列表，第一項是當前映射
                var target = buffer[..(int)length].ToString().Split('\0', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault();
                if (!string.IsNullOrEmpty(target))
                {
                    result[target] = drive;
                }
            }
        }

        return result;
    }

    [LibraryImport("kernel32.dll", EntryPoint = "QueryDosDeviceW", StringMarshalling = StringMarshalling.Utf16, SetLastError = true)]
    private static partial uint QueryDosDevice(string deviceName, ref char targetPath, uint max);
}
