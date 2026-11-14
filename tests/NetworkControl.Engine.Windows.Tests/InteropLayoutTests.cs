using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using NetworkControl.Engine.Windows.Interop;
using NetworkControl.Engine.Windows.Platform;

namespace NetworkControl.Engine.Windows.Tests;

/// <summary>
/// 互操作結構體的大小與偏移必須與 Windows SDK 10.0.26100 頭文件在 64 位平台（x64 / ARM64）上的佈局一致。
/// 期望值按頭文件的字段順序和自然對齊手工推算。
/// </summary>
public class InteropLayoutTests
{
    [Theory]
    [InlineData(typeof(FwpValue0), 16)]
    [InlineData(typeof(FwpByteBlob), 16)]
    [InlineData(typeof(FwpRange0), 32)]
    [InlineData(typeof(FwpV6AddrAndMask), 17)]
    [InlineData(typeof(FwpmSession0), 72)]
    [InlineData(typeof(FwpmProvider0), 64)]
    [InlineData(typeof(FwpmSublayer0), 72)]
    [InlineData(typeof(FwpmFilterCondition0), 40)]
    [InlineData(typeof(FwpmFilter0), 200)]
    [InlineData(typeof(FwpmNetEventHeader1), 144)]
    [InlineData(typeof(FwpmNetEvent1), 160)]
    [InlineData(typeof(FwpmNetEventClassifyDrop1), 32)]
    [InlineData(typeof(FwpmNetEventEnumTemplate0), 32)]
    [InlineData(typeof(FwpmNetEventSubscription0), 32)]
    [InlineData(typeof(WindowsAppInspector.WinTrustData), 88)]
    [InlineData(typeof(WindowsAppInspector.WinTrustFileInfo), 32)]
    [InlineData(typeof(CatalogSignature.WinTrustCatalogInfo), 72)]
    public void Struct_sizes_match_sdk(Type type, int expected)
    {
        Assert.SkipUnless(Environment.Is64BitProcess, "Layouts are asserted for 64-bit processes.");
        Assert.Equal(expected, Marshal.SizeOf(type));
    }

    [Theory]
    [InlineData(typeof(FwpmFilter0), nameof(FwpmFilter0.ProviderKey), 40)]
    [InlineData(typeof(FwpmFilter0), nameof(FwpmFilter0.LayerKey), 64)]
    [InlineData(typeof(FwpmFilter0), nameof(FwpmFilter0.Weight), 96)]
    [InlineData(typeof(FwpmFilter0), nameof(FwpmFilter0.FilterCondition), 120)]
    [InlineData(typeof(FwpmFilter0), nameof(FwpmFilter0.Action), 128)]
    [InlineData(typeof(FwpmFilter0), nameof(FwpmFilter0.Context), 152)]
    [InlineData(typeof(FwpmFilter0), nameof(FwpmFilter0.FilterId), 176)]
    [InlineData(typeof(FwpmFilterCondition0), nameof(FwpmFilterCondition0.ConditionValue), 24)]
    [InlineData(typeof(FwpmSublayer0), nameof(FwpmSublayer0.Weight), 64)]
    [InlineData(typeof(FwpmNetEventHeader1), nameof(FwpmNetEventHeader1.LocalAddr), 20)]
    [InlineData(typeof(FwpmNetEventHeader1), nameof(FwpmNetEventHeader1.RemoteAddr), 36)]
    [InlineData(typeof(FwpmNetEventHeader1), nameof(FwpmNetEventHeader1.LocalPort), 52)]
    [InlineData(typeof(FwpmNetEventHeader1), nameof(FwpmNetEventHeader1.AppId), 64)]
    [InlineData(typeof(FwpmNetEventHeader1), nameof(FwpmNetEventHeader1.UserId), 80)]
    [InlineData(typeof(FwpmNetEvent1), nameof(FwpmNetEvent1.Type), 144)]
    [InlineData(typeof(FwpmNetEvent1), nameof(FwpmNetEvent1.Detail), 152)]
    [InlineData(typeof(FwpmNetEventSubscription0), nameof(FwpmNetEventSubscription0.SessionKey), 12)]
    [InlineData(typeof(WindowsAppInspector.WinTrustData), nameof(WindowsAppInspector.WinTrustData.File), 40)]
    [InlineData(typeof(WindowsAppInspector.WinTrustData), nameof(WindowsAppInspector.WinTrustData.StateData), 56)]
    public void Field_offsets_match_sdk(Type type, string field, int expected)
    {
        Assert.SkipUnless(Environment.Is64BitProcess, "Layouts are asserted for 64-bit processes.");
        Assert.Equal(expected, Marshal.OffsetOf(type, field).ToInt32());
    }

    [Fact]
    public void Blittable_size_equals_marshal_size()
    {
        // 結構體直接以指針傳給本機代碼，託管大小必須與封送大小一致
        Assert.Equal(Marshal.SizeOf<FwpmFilter0>(), Unsafe.SizeOf<FwpmFilter0>());
        Assert.Equal(Marshal.SizeOf<FwpmNetEvent1>(), Unsafe.SizeOf<FwpmNetEvent1>());
    }

    [Fact]
    public void Host_order_ipv4_is_converted()
    {
        Assert.Equal("192.168.1.20", WfpEngine.FromHostOrder(0xC0A80114).ToString());
    }
}
