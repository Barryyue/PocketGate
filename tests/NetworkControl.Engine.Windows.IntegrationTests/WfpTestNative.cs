using System.Runtime.InteropServices;
using NetworkControl.Engine.Windows.Interop;

namespace NetworkControl.Engine.Windows.IntegrationTests;

/// <summary>
/// 測試專用的 WFP 互操作輔助：用獨立的非動態會話枚舉測試提供程序的過濾器，
/// 確認動態會話 Dispose 後沒有殘留。結構體佈局按 Windows SDK 10.0.26100 的 fwpmtypes.h。
/// </summary>
internal static unsafe partial class WfpTestNative
{
    private const string Library = "fwpuclnt.dll";

    /// <summary>FWPM_FILTER_ENUM_TEMPLATE0。</summary>
    [StructLayout(LayoutKind.Sequential)]
    internal struct FwpmFilterEnumTemplate0
    {
        public nint ProviderKey;
        public Guid LayerKey;
        public uint EnumType;
        public uint Flags;
        public nint ProviderContextTemplate;
        public uint NumFilterConditions;
        public nint FilterCondition;
        public uint ActionMask;
        public nint CalloutKey;
    }

    /// <summary>指定提供程序 / 子層在 ALE_AUTH_CONNECT_V4 上的過濾器數量。</summary>
    public static int CountFilters(Guid providerKey, Guid subLayerKey)
    {
        WfpSession.Check("FwpmEngineOpen0", FwpNative.FwpmEngineOpen0(0, FwpConstants.RpcCAuthnWinNt, 0, null, out var engine));
        try
        {
            var provider = providerKey;
            var template = new FwpmFilterEnumTemplate0
            {
                ProviderKey = (nint)(&provider),
                LayerKey = FwpGuids.LayerAleAuthConnectV4,
                EnumType = 0,
            };

            WfpSession.Check(
                "FwpmFilterCreateEnumHandle0",
                FwpmFilterCreateEnumHandle0(engine, &template, out var handle));
            try
            {
                var count = 0;
                while (true)
                {
                    WfpSession.Check(
                        "FwpmFilterEnum0",
                        FwpmFilterEnum0(engine, handle, 128, out var entries, out var returned));
                    if (returned == 0)
                    {
                        return count;
                    }

                    try
                    {
                        var filters = (FwpmFilter0**)entries;
                        for (var i = 0; i < returned; i++)
                        {
                            var filter = filters[i];
                            if (filter->ProviderKey != 0
                                && *(Guid*)filter->ProviderKey == providerKey
                                && filter->SubLayerKey == subLayerKey)
                            {
                                count++;
                            }
                        }
                    }
                    finally
                    {
                        FwpNative.FwpmFreeMemory0(ref entries);
                    }
                }
            }
            finally
            {
                FwpmFilterDestroyEnumHandle0(engine, handle);
            }
        }
        finally
        {
            FwpNative.FwpmEngineClose0(engine);
        }
    }

    /// <summary>按過濾器 ID 查詢是否仍然存在。</summary>
    public static bool FilterExists(ulong filterId)
    {
        WfpSession.Check("FwpmEngineOpen0", FwpNative.FwpmEngineOpen0(0, FwpConstants.RpcCAuthnWinNt, 0, null, out var engine));
        try
        {
            var code = FwpmFilterGetById0(engine, filterId, out var filter);
            if (code == FwpConstants.ErrorSuccess)
            {
                FwpNative.FwpmFreeMemory0(ref filter);
                return true;
            }

            if (code == FwpConstants.FwpEFilterNotFound)
            {
                return false;
            }

            throw new WfpException("FwpmFilterGetById0", code);
        }
        finally
        {
            FwpNative.FwpmEngineClose0(engine);
        }
    }

    [LibraryImport(Library)]
    private static partial uint FwpmFilterCreateEnumHandle0(
        nint engineHandle,
        FwpmFilterEnumTemplate0* enumTemplate,
        out nint enumHandle);

    [LibraryImport(Library)]
    private static partial uint FwpmFilterEnum0(
        nint engineHandle,
        nint enumHandle,
        uint numEntriesRequested,
        out nint entries,
        out uint numEntriesReturned);

    [LibraryImport(Library)]
    private static partial uint FwpmFilterDestroyEnumHandle0(nint engineHandle, nint enumHandle);

    [LibraryImport(Library)]
    private static partial uint FwpmFilterGetById0(nint engineHandle, ulong id, out nint filter);
}
