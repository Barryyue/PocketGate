using System.ComponentModel;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using NetworkControl.Core.Domain;
using NetworkControl.Engine.Windows.Compilation;

namespace NetworkControl.Engine.Windows.Interop;

/// <summary>WFP 調用失敗。</summary>
public sealed class WfpException(string operation, uint code)
    : Win32Exception(unchecked((int)code), $"{operation} failed: 0x{code:X8} ({new Win32Exception(unchecked((int)code)).Message})")
{
    public uint Code { get; } = code;
}

/// <summary>
/// 一個動態 WFP 會話：會話中創建的所有對象（提供者、子層、過濾器）在會話關閉或進程退出時由 BFE 自動刪除，
/// 因此服務崩潰時網絡自動恢復暢通（fail-open，設計文檔 §10.7）。
/// </summary>
internal sealed unsafe class WfpSession : IDisposable
{
    public static readonly Guid ProviderKey = new("6c1f1d53-6f5b-4f3e-9b2e-8b7a4c1a0e01");
    public static readonly Guid SubLayerKey = new("6c1f1d53-6f5b-4f3e-9b2e-8b7a4c1a0e02");

    private readonly Dictionary<string, byte[]> _appIdCache = new(StringComparer.OrdinalIgnoreCase);
    private nint _engine;

    private WfpSession(nint engine) => _engine = engine;

    public nint Handle => _engine;

    /// <summary>
    /// FwpmGetAppIdFromFileName0 失敗時的應用 ID 來源。它需要打開文件，而部分安全 / 防泄密軟件會在驅動層
    /// 拒絕訪問自身文件（返回「找不到文件」），導致允許規則無法下發、應用被靜默阻斷。
    /// </summary>
    public Func<string, byte[]?>? AppIdFallback { get; set; }

    public static WfpSession Open(string displayName)
    {
        using var name = new NativeString(displayName);
        var session = new FwpmSession0
        {
            DisplayData = new FwpmDisplayData0 { Name = name.Pointer },
            Flags = FwpConstants.SessionFlagDynamic,
            TxnWaitTimeoutInMSec = 5000,
        };

        Check("FwpmEngineOpen0", FwpNative.FwpmEngineOpen0(0, FwpConstants.RpcCAuthnWinNt, 0, &session, out var engine));
        var result = new WfpSession(engine);
        try
        {
            result.EnsureProviderAndSubLayer(displayName);
            return result;
        }
        catch
        {
            result.Dispose();
            throw;
        }
    }

    /// <summary>在一個事務中刪除和添加過濾器；任何一步失敗都整體回滾。</summary>
    public IReadOnlyDictionary<FilterSpec, ulong> Apply(IReadOnlyCollection<ulong> remove, IReadOnlyCollection<FilterSpec> add, Action<FilterSpec, Exception> onSkipped)
    {
        var added = new Dictionary<FilterSpec, ulong>();
        Check("FwpmTransactionBegin0", FwpNative.FwpmTransactionBegin0(_engine, 0));
        try
        {
            foreach (var id in remove)
            {
                var code = FwpNative.FwpmFilterDeleteById0(_engine, id);
                if (code != FwpConstants.ErrorSuccess && code != FwpConstants.FwpEFilterNotFound)
                {
                    Check("FwpmFilterDeleteById0", code);
                }
            }

            foreach (var spec in add)
            {
                byte[]? appId = null;
                if (spec.AppPath is { } path && !TryGetAppId(path, out appId, out var error))
                {
                    // 路徑無法轉換（文件或卷不存在）：跳過這個過濾器，不影響其他規則
                    onSkipped(spec, error!);
                    continue;
                }

                added[spec] = AddFilter(spec, appId);
            }

            Check("FwpmTransactionCommit0", FwpNative.FwpmTransactionCommit0(_engine));
            return added;
        }
        catch
        {
            FwpNative.FwpmTransactionAbort0(_engine);
            throw;
        }
    }

    /// <summary>
    /// 設置全局引擎選項。WFP 不允許在動態會話中調用 FwpmEngineSetOption0（FWP_E_DYNAMIC_SESSION_IN_PROGRESS），
    /// 因此臨時打開一個非動態會話的句柄，設置後立即關閉；過濾器仍然留在動態會話中。
    /// </summary>
    public static void SetGlobalOption(FwpmEngineOption option, uint value)
    {
        Check("FwpmEngineOpen0", FwpNative.FwpmEngineOpen0(0, FwpConstants.RpcCAuthnWinNt, 0, null, out var engine));
        try
        {
            var v = new FwpValue0 { Type = FwpDataType.UInt32, UInt32 = value };
            Check("FwpmEngineSetOption0", FwpNative.FwpmEngineSetOption0(engine, option, &v));
        }
        finally
        {
            FwpNative.FwpmEngineClose0(engine);
        }
    }

    /// <summary>讀取全局引擎選項（同樣使用臨時的非動態會話句柄）。</summary>
    public static uint? GetGlobalOption(FwpmEngineOption option)
    {
        if (FwpNative.FwpmEngineOpen0(0, FwpConstants.RpcCAuthnWinNt, 0, null, out var engine) != FwpConstants.ErrorSuccess)
        {
            return null;
        }

        try
        {
            if (FwpNative.FwpmEngineGetOption0(engine, option, out var value) != FwpConstants.ErrorSuccess || value == 0)
            {
                return null;
            }

            try
            {
                var v = (FwpValue0*)value;
                return v->Type == FwpDataType.UInt32 ? v->UInt32 : null;
            }
            finally
            {
                FwpNative.FwpmFreeMemory0(ref value);
            }
        }
        finally
        {
            FwpNative.FwpmEngineClose0(engine);
        }
    }

    public void Dispose()
    {
        if (_engine != 0)
        {
            FwpNative.FwpmEngineClose0(_engine);
            _engine = 0;
        }
    }

    internal static void Check(string operation, uint code)
    {
        if (code != FwpConstants.ErrorSuccess)
        {
            throw new WfpException(operation, code);
        }
    }

    private void EnsureProviderAndSubLayer(string displayName)
    {
        using var name = new NativeString(displayName);
        var providerKey = ProviderKey;

        Check("FwpmTransactionBegin0", FwpNative.FwpmTransactionBegin0(_engine, 0));
        try
        {
            var provider = new FwpmProvider0 { ProviderKey = providerKey, DisplayData = new FwpmDisplayData0 { Name = name.Pointer } };
            CheckAllowExisting("FwpmProviderAdd0", FwpNative.FwpmProviderAdd0(_engine, &provider, 0));

            var subLayer = new FwpmSublayer0
            {
                SubLayerKey = SubLayerKey,
                DisplayData = new FwpmDisplayData0 { Name = name.Pointer },
                ProviderKey = (nint)(&providerKey),
                Weight = 0x8000,
            };
            CheckAllowExisting("FwpmSubLayerAdd0", FwpNative.FwpmSubLayerAdd0(_engine, &subLayer, 0));

            Check("FwpmTransactionCommit0", FwpNative.FwpmTransactionCommit0(_engine));
        }
        catch
        {
            FwpNative.FwpmTransactionAbort0(_engine);
            throw;
        }
    }

    private ulong AddFilter(FilterSpec spec, byte[]? appId)
    {
        using var memory = new NativeArena();
        var conditions = new List<FwpmFilterCondition0>();

        if (spec.LoopbackOnly)
        {
            conditions.Add(new FwpmFilterCondition0
            {
                FieldKey = FwpGuids.ConditionFlags,
                MatchType = FwpMatchType.FlagsAllSet,
                ConditionValue = new FwpValue0 { Type = FwpDataType.UInt32, UInt32 = FwpConstants.ConditionFlagIsLoopback },
            });
        }

        if (appId is not null)
        {
            var data = memory.Copy(appId);
            var blob = memory.Alloc<FwpByteBlob>();
            *blob = new FwpByteBlob { Size = (uint)appId.Length, Data = data };
            conditions.Add(new FwpmFilterCondition0
            {
                FieldKey = FwpGuids.ConditionAleAppId,
                MatchType = FwpMatchType.Equal,
                ConditionValue = new FwpValue0 { Type = FwpDataType.ByteBlobType, Pointer = (nint)blob },
            });
        }

        // 同一字段的多個條件之間是 OR 關係
        foreach (var network in spec.RemoteNetworks)
        {
            conditions.Add(new FwpmFilterCondition0
            {
                FieldKey = FwpGuids.ConditionIpRemoteAddress,
                MatchType = FwpMatchType.Equal,
                ConditionValue = AddressCondition(network, memory),
            });
        }

        if (spec.RemotePort is { } remotePort)
        {
            conditions.Add(PortCondition(FwpGuids.ConditionIpRemotePort, remotePort, memory));
        }

        if (spec.LocalPort is { } localPort)
        {
            conditions.Add(PortCondition(FwpGuids.ConditionIpLocalPort, localPort, memory));
        }

        if (spec.Protocol is { } protocol)
        {
            conditions.Add(new FwpmFilterCondition0
            {
                FieldKey = FwpGuids.ConditionIpProtocol,
                MatchType = FwpMatchType.Equal,
                ConditionValue = new FwpValue0 { Type = FwpDataType.UInt8, UInt8 = (byte)protocol },
            });
        }

        var conditionArray = memory.Alloc<FwpmFilterCondition0>(Math.Max(1, conditions.Count));
        for (var i = 0; i < conditions.Count; i++)
        {
            conditionArray[i] = conditions[i];
        }

        var weight = memory.Alloc<ulong>();
        *weight = spec.Weight;
        var providerKey = memory.Alloc<Guid>();
        *providerKey = ProviderKey;
        var name = memory.String(spec.Name);

        var filter = new FwpmFilter0
        {
            DisplayData = new FwpmDisplayData0 { Name = name },
            ProviderKey = (nint)providerKey,
            LayerKey = spec.Family == IpFamily.V4 ? FwpGuids.LayerAleAuthConnectV4 : FwpGuids.LayerAleAuthConnectV6,
            SubLayerKey = SubLayerKey,
            Weight = new FwpValue0 { Type = FwpDataType.UInt64, Pointer = (nint)weight },
            NumFilterConditions = (uint)conditions.Count,
            FilterCondition = conditions.Count > 0 ? (nint)conditionArray : 0,
            Action = new FwpmAction0 { Type = spec.Permit ? FwpConstants.ActionPermit : FwpConstants.ActionBlock },
        };

        Check("FwpmFilterAdd0", FwpNative.FwpmFilterAdd0(_engine, &filter, 0, out var id));
        return id;
    }

    private static FwpValue0 AddressCondition(IPNetwork network, NativeArena memory)
    {
        if (network.BaseAddress.AddressFamily == AddressFamily.InterNetwork)
        {
            var bytes = network.BaseAddress.GetAddressBytes();
            var mask = network.PrefixLength == 0 ? 0u : uint.MaxValue << (32 - network.PrefixLength);
            var v4 = memory.Alloc<FwpV4AddrAndMask>();
            // WFP 的 IPv4 地址和掩碼使用主機字節序
            *v4 = new FwpV4AddrAndMask { Address = (uint)((bytes[0] << 24) | (bytes[1] << 16) | (bytes[2] << 8) | bytes[3]), Mask = mask };
            return new FwpValue0 { Type = FwpDataType.V4AddrMask, Pointer = (nint)v4 };
        }

        var v6 = memory.Alloc<FwpV6AddrAndMask>();
        var v6Bytes = network.BaseAddress.GetAddressBytes();
        for (var i = 0; i < 16; i++)
        {
            v6->Address[i] = v6Bytes[i];
        }

        v6->PrefixLength = (byte)network.PrefixLength;
        return new FwpValue0 { Type = FwpDataType.V6AddrMask, Pointer = (nint)v6 };
    }

    private static FwpmFilterCondition0 PortCondition(Guid field, PortRange range, NativeArena memory)
    {
        if (range.IsSingle)
        {
            return new FwpmFilterCondition0
            {
                FieldKey = field,
                MatchType = FwpMatchType.Equal,
                ConditionValue = new FwpValue0 { Type = FwpDataType.UInt16, UInt16 = range.From },
            };
        }

        var r = memory.Alloc<FwpRange0>();
        *r = new FwpRange0
        {
            Low = new FwpValue0 { Type = FwpDataType.UInt16, UInt16 = range.From },
            High = new FwpValue0 { Type = FwpDataType.UInt16, UInt16 = range.To },
        };
        return new FwpmFilterCondition0
        {
            FieldKey = field,
            MatchType = FwpMatchType.Range,
            ConditionValue = new FwpValue0 { Type = FwpDataType.RangeType, Pointer = (nint)r },
        };
    }

    private bool TryGetAppId(string path, out byte[]? appId, out Exception? error)
    {
        if (_appIdCache.TryGetValue(path, out appId))
        {
            error = null;
            return true;
        }

        var code = FwpNative.FwpmGetAppIdFromFileName0(path, out var blob);
        if (code != FwpConstants.ErrorSuccess)
        {
            if (AppIdFallback?.Invoke(path) is { Length: > 0 } fallback)
            {
                appId = fallback;
                _appIdCache[path] = fallback;
                error = null;
                return true;
            }

            appId = null;
            error = new WfpException($"FwpmGetAppIdFromFileName0({path})", code);
            return false;
        }

        try
        {
            appId = new ReadOnlySpan<byte>((void*)blob->Data, (int)blob->Size).ToArray();
            _appIdCache[path] = appId;
            error = null;
            return true;
        }
        finally
        {
            var p = (nint)blob;
            FwpNative.FwpmFreeMemory0(ref p);
        }
    }

    private static void CheckAllowExisting(string operation, uint code)
    {
        if (code != FwpConstants.FwpEAlreadyExists)
        {
            Check(operation, code);
        }
    }

    /// <summary>一次調用期間使用的非託管內存，調用結束後統一釋放。</summary>
    private sealed class NativeArena : IDisposable
    {
        private readonly List<nint> _blocks = [];

        public T* Alloc<T>(int count = 1)
            where T : unmanaged
        {
            var p = NativeMemory.AllocZeroed((nuint)(sizeof(T) * count));
            _blocks.Add((nint)p);
            return (T*)p;
        }

        public nint Copy(byte[] bytes)
        {
            var p = Alloc<byte>(bytes.Length);
            bytes.CopyTo(new Span<byte>(p, bytes.Length));
            return (nint)p;
        }

        /// <summary>以 NUL 結尾的 UTF-16 字符串。</summary>
        public nint String(string value)
        {
            var p = Alloc<char>(value.Length + 1);
            value.AsSpan().CopyTo(new Span<char>(p, value.Length));
            return (nint)p;
        }

        public void Dispose()
        {
            foreach (var block in _blocks)
            {
                NativeMemory.Free((void*)block);
            }

            _blocks.Clear();
        }
    }

    private readonly struct NativeString(string value) : IDisposable
    {
        public nint Pointer { get; } = Marshal.StringToHGlobalUni(value);

        public void Dispose() => Marshal.FreeHGlobal(Pointer);
    }
}
