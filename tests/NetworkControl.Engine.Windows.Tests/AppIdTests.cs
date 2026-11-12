using NetworkControl.Engine.Windows.Interop;
using NetworkControl.Engine.Windows.Platform;

namespace NetworkControl.Engine.Windows.Tests;

public class AppIdTests
{
    [Fact]
    public void Dos_path_maps_back_to_device_path()
    {
        var resolver = new DevicePathResolver(() => new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            [@"\Device\HarddiskVolume3"] = "C:",
        });

        Assert.Equal(@"\Device\HarddiskVolume3\Apps\a.exe", resolver.ToDevicePath(@"C:\Apps\a.exe"));
        Assert.Null(resolver.ToDevicePath(@"Z:\x.exe"));
        Assert.Null(resolver.ToDevicePath(@"\\server\share\x.exe"));
    }

    /// <summary>
    /// 自行構造的應用 ID 必須與 WFP API 的輸出逐字節一致，否則兜底下發的規則匹配不到任何連接。
    /// FwpmGetAppIdFromFileName0 不需要管理員權限，可以直接對照系統自帶的程序驗證。
    /// </summary>
    [Fact]
    public unsafe void Built_app_id_matches_wfp_api_output()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "Windows only.");
        var notepad = Path.Combine(Environment.SystemDirectory, "notepad.exe");
        Assert.SkipUnless(File.Exists(notepad), "notepad.exe is not present.");

        var code = FwpNative.FwpmGetAppIdFromFileName0(notepad, out var blob);
        Assert.Equal(0u, code);
        byte[] expected;
        try
        {
            expected = new ReadOnlySpan<byte>((void*)blob->Data, (int)blob->Size).ToArray();
        }
        finally
        {
            var p = (nint)blob;
            FwpNative.FwpmFreeMemory0(ref p);
        }

        var built = new DevicePathResolver().BuildAppId(notepad);

        Assert.NotNull(built);
        Assert.Equal(expected, built);
    }
}
