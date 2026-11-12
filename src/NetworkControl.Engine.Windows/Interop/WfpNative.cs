using System.Runtime.InteropServices;

namespace NetworkControl.Engine.Windows.Interop;

// WFP 用戶態 API 的最小互操作定義。
// 所有結構體佈局都按 Windows SDK 10.0.26100 的 fwptypes.h / fwpmtypes.h 逐字段對照，
// 並在 NetworkControl.Engine.Windows.Tests 中斷言大小與偏移（x64 / ARM64）。
// 修改任何字段前請先核對頭文件，錯誤的佈局會導致內存損壞而不是調用失敗。

internal static class FwpGuids
{
    public static readonly Guid LayerAleAuthConnectV4 = new(0xc38d57d1, 0x05a7, 0x4c33, 0x90, 0x4f, 0x7f, 0xbc, 0xee, 0xe6, 0x0e, 0x82);
    public static readonly Guid LayerAleAuthConnectV6 = new(0x4a72393b, 0x319f, 0x44bc, 0x84, 0xc3, 0xba, 0x54, 0xdc, 0xb3, 0xb6, 0xb4);
    public static readonly Guid LayerAleAuthRecvAcceptV4 = new(0xe1cd9fe7, 0xf4b5, 0x4273, 0x96, 0xc0, 0x59, 0x2e, 0x48, 0x7b, 0x86, 0x50);
    public static readonly Guid LayerAleAuthRecvAcceptV6 = new(0xa3b42c97, 0x9f04, 0x4672, 0xb8, 0x7e, 0xce, 0xe9, 0xc4, 0x83, 0x25, 0x7f);

    public static readonly Guid ConditionAleAppId = new(0xd78e1e87, 0x8644, 0x4ea5, 0x94, 0x37, 0xd8, 0x09, 0xec, 0xef, 0xc9, 0x71);
    public static readonly Guid ConditionIpRemoteAddress = new(0xb235ae9a, 0x1d64, 0x49b8, 0xa4, 0x4c, 0x5f, 0xf3, 0xd9, 0x09, 0x50, 0x45);
    public static readonly Guid ConditionIpRemotePort = new(0xc35a604d, 0xd22b, 0x4e1a, 0x91, 0xb4, 0x68, 0xf6, 0x74, 0xee, 0x67, 0x4b);
    public static readonly Guid ConditionIpLocalPort = new(0x0c1ba1af, 0x5765, 0x453f, 0xaf, 0x22, 0xa8, 0xf7, 0x91, 0xac, 0x77, 0x5b);
    public static readonly Guid ConditionIpProtocol = new(0x3971ef2b, 0x623e, 0x4f9a, 0x8c, 0xb1, 0x6e, 0x79, 0xb8, 0x06, 0xb9, 0xa7);
    public static readonly Guid ConditionFlags = new(0x632ce23b, 0x5167, 0x435c, 0x86, 0xd7, 0xe9, 0x03, 0x68, 0x4a, 0xa8, 0x0c);
}

internal static class FwpConstants
{
    public const uint RpcCAuthnWinNt = 10;
    public const uint SessionFlagDynamic = 0x00000001;
    public const uint ActionFlagTerminating = 0x00001000;
    public const uint ActionBlock = 0x00000001 | ActionFlagTerminating;
    public const uint ActionPermit = 0x00000002 | ActionFlagTerminating;
    public const uint ConditionFlagIsLoopback = 0x00000001;

    public const uint NetEventFlagIpProtocolSet = 0x00000001;
    public const uint NetEventFlagLocalAddrSet = 0x00000002;
    public const uint NetEventFlagRemoteAddrSet = 0x00000004;
    public const uint NetEventFlagLocalPortSet = 0x00000008;
    public const uint NetEventFlagRemotePortSet = 0x00000010;
    public const uint NetEventFlagAppIdSet = 0x00000020;
    public const uint NetEventFlagUserIdSet = 0x00000040;
    public const uint NetEventFlagIpVersionSet = 0x00000100;

    // Win32 / WFP 錯誤碼
    public const uint ErrorSuccess = 0;
    public const uint FwpEAlreadyExists = 0x80320009;
    public const uint FwpEFilterNotFound = 0x80320003;
}

internal enum FwpDataType : uint
{
    Empty = 0,
    UInt8 = 1,
    UInt16 = 2,
    UInt32 = 3,
    UInt64 = 4,
    ByteBlobType = 12,
    V4AddrMask = 0x100,
    V6AddrMask = 0x101,
    RangeType = 0x102,
}

internal enum FwpMatchType : uint
{
    Equal = 0,
    Range = 5,
    FlagsAllSet = 6,
}

internal enum FwpIpVersion : uint
{
    V4 = 0,
    V6 = 1,
    None = 2,
}

internal enum FwpmEngineOption : uint
{
    CollectNetEvents = 0,
    NetEventMatchAnyKeywords = 1,
}

internal enum FwpmNetEventType : uint
{
    ClassifyDrop = 3,
    ClassifyAllow = 6,
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpByteBlob
{
    public uint Size;
    public nint Data;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmDisplayData0
{
    public nint Name;
    public nint Description;
}

/// <summary>FWP_VALUE0 / FWP_CONDITION_VALUE0：類型 + 8 字節聯合（UINT64 等大值以指針傳遞）。</summary>
[StructLayout(LayoutKind.Explicit, Size = 16)]
internal struct FwpValue0
{
    [FieldOffset(0)] public FwpDataType Type;
    [FieldOffset(8)] public byte UInt8;
    [FieldOffset(8)] public ushort UInt16;
    [FieldOffset(8)] public uint UInt32;
    [FieldOffset(8)] public nint Pointer;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpV4AddrAndMask
{
    /// <summary>主機字節序。</summary>
    public uint Address;

    /// <summary>主機字節序。</summary>
    public uint Mask;
}

[StructLayout(LayoutKind.Sequential)]
internal unsafe struct FwpV6AddrAndMask
{
    public fixed byte Address[16];
    public byte PrefixLength;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpRange0
{
    public FwpValue0 Low;
    public FwpValue0 High;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmSession0
{
    public Guid SessionKey;
    public FwpmDisplayData0 DisplayData;
    public uint Flags;
    public uint TxnWaitTimeoutInMSec;
    public uint ProcessId;
    public nint Sid;
    public nint Username;
    public int KernelMode;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmProvider0
{
    public Guid ProviderKey;
    public FwpmDisplayData0 DisplayData;
    public uint Flags;
    public FwpByteBlob ProviderData;
    public nint ServiceName;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmSublayer0
{
    public Guid SubLayerKey;
    public FwpmDisplayData0 DisplayData;
    public uint Flags;
    public nint ProviderKey;
    public FwpByteBlob ProviderData;
    public ushort Weight;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmFilterCondition0
{
    public Guid FieldKey;
    public FwpMatchType MatchType;
    public FwpValue0 ConditionValue;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmAction0
{
    public uint Type;
    public Guid FilterTypeOrCalloutKey;
}

/// <summary>FWPM_FILTER0 中 rawContext / providerContextKey 的聯合（按 UINT64 對齊）。</summary>
[StructLayout(LayoutKind.Explicit, Size = 16)]
internal struct FwpmFilterContext
{
    [FieldOffset(0)] public ulong RawContext;
    [FieldOffset(0)] public Guid ProviderContextKey;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmFilter0
{
    public Guid FilterKey;
    public FwpmDisplayData0 DisplayData;
    public uint Flags;
    public nint ProviderKey;
    public FwpByteBlob ProviderData;
    public Guid LayerKey;
    public Guid SubLayerKey;
    public FwpValue0 Weight;
    public uint NumFilterConditions;
    public nint FilterCondition;
    public FwpmAction0 Action;
    public FwpmFilterContext Context;
    public nint Reserved;
    public ulong FilterId;
    public FwpValue0 EffectiveWeight;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FileTime
{
    public uint Low;
    public uint High;
}

[StructLayout(LayoutKind.Explicit, Size = 16)]
internal unsafe struct FwpAddress16
{
    [FieldOffset(0)] public uint V4;
    [FieldOffset(0)] public fixed byte V6[16];
}

/// <summary>
/// FWPM_NET_EVENT_HEADER1 尾部的保留聯合（只為保證總大小與偏移正確）。
/// C 定義為 <c>struct { FWP_AF reserved1; union { struct { ... UINT64 reserved10; }; }; }</c>：
/// 內層結構含 UINT64，按 8 字節對齊，因此必須保持嵌套，不能攤平。
/// </summary>
[StructLayout(LayoutKind.Sequential)]
internal struct FwpmNetEventHeader1Reserved
{
    public uint Reserved1;
    public FwpmNetEventHeader1ReservedInner Inner;
}

[StructLayout(LayoutKind.Sequential)]
internal unsafe struct FwpmNetEventHeader1ReservedInner
{
    public fixed byte Reserved2[6];
    public fixed byte Reserved3[6];
    public uint Reserved4;
    public uint Reserved5;
    public ushort Reserved6;
    public uint Reserved7;
    public uint Reserved8;
    public ushort Reserved9;
    public ulong Reserved10;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmNetEventHeader1
{
    public FileTime TimeStamp;
    public uint Flags;
    public FwpIpVersion IpVersion;
    public byte IpProtocol;
    public FwpAddress16 LocalAddr;
    public FwpAddress16 RemoteAddr;
    public ushort LocalPort;
    public ushort RemotePort;
    public uint ScopeId;
    public FwpByteBlob AppId;
    public nint UserId;
    public FwpmNetEventHeader1Reserved Reserved;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmNetEvent1
{
    public FwpmNetEventHeader1 Header;
    public FwpmNetEventType Type;
    public nint Detail;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmNetEventClassifyDrop1
{
    public ulong FilterId;
    public ushort LayerId;
    public uint ReauthReason;
    public uint OriginalProfile;
    public uint CurrentProfile;
    public uint MsFwpDirection;
    public int IsLoopback;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmNetEventEnumTemplate0
{
    public FileTime StartTime;
    public FileTime EndTime;
    public uint NumFilterConditions;
    public nint FilterCondition;
}

[StructLayout(LayoutKind.Sequential)]
internal struct FwpmNetEventSubscription0
{
    public nint EnumTemplate;
    public uint Flags;
    public Guid SessionKey;
}

internal static unsafe partial class FwpNative
{
    private const string Library = "fwpuclnt.dll";

    [LibraryImport(Library)]
    public static partial uint FwpmEngineOpen0(nint serverName, uint authnService, nint authIdentity, FwpmSession0* session, out nint engineHandle);

    [LibraryImport(Library)]
    public static partial uint FwpmEngineClose0(nint engineHandle);

    [LibraryImport(Library)]
    public static partial uint FwpmEngineGetOption0(nint engineHandle, FwpmEngineOption option, out nint value);

    [LibraryImport(Library)]
    public static partial uint FwpmEngineSetOption0(nint engineHandle, FwpmEngineOption option, FwpValue0* newValue);

    [LibraryImport(Library)]
    public static partial uint FwpmTransactionBegin0(nint engineHandle, uint flags);

    [LibraryImport(Library)]
    public static partial uint FwpmTransactionCommit0(nint engineHandle);

    [LibraryImport(Library)]
    public static partial uint FwpmTransactionAbort0(nint engineHandle);

    [LibraryImport(Library)]
    public static partial uint FwpmProviderAdd0(nint engineHandle, FwpmProvider0* provider, nint sd);

    [LibraryImport(Library)]
    public static partial uint FwpmSubLayerAdd0(nint engineHandle, FwpmSublayer0* subLayer, nint sd);

    [LibraryImport(Library)]
    public static partial uint FwpmFilterAdd0(nint engineHandle, FwpmFilter0* filter, nint sd, out ulong id);

    [LibraryImport(Library)]
    public static partial uint FwpmFilterDeleteById0(nint engineHandle, ulong id);

    [LibraryImport(Library, StringMarshalling = StringMarshalling.Utf16)]
    public static partial uint FwpmGetAppIdFromFileName0(string fileName, out FwpByteBlob* appId);

    [LibraryImport(Library)]
    public static partial void FwpmFreeMemory0(ref nint p);

    [LibraryImport(Library)]
    public static partial uint FwpmNetEventSubscribe0(
        nint engineHandle,
        FwpmNetEventSubscription0* subscription,
        delegate* unmanaged[Stdcall]<nint, FwpmNetEvent1*, void> callback,
        nint context,
        out nint eventsHandle);

    [LibraryImport(Library)]
    public static partial uint FwpmNetEventUnsubscribe0(nint engineHandle, nint eventsHandle);
}
