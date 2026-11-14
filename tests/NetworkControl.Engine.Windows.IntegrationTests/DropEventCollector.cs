using System.Collections.Concurrent;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using NetworkControl.Engine.Windows.Interop;

namespace NetworkControl.Engine.Windows.IntegrationTests;

/// <summary>收集 WFP 的 ClassifyDrop 網絡事件，用於確認阻斷過濾器真的產生了事件。</summary>
internal sealed class DropEventCollector : IDisposable
{
    private readonly ConcurrentQueue<ulong> _filterIds = new();
    private readonly SemaphoreSlim _signal = new(0);
    private readonly GCHandle _self;
    private readonly nint _engineHandle;
    private nint _subscription;

    public unsafe DropEventCollector(nint engineHandle)
    {
        _engineHandle = engineHandle;
        _self = GCHandle.Alloc(this);
        var subscription = new FwpmNetEventSubscription0();
        WfpSession.Check(
            "FwpmNetEventSubscribe0",
            FwpNative.FwpmNetEventSubscribe0(engineHandle, &subscription, &OnEvent, GCHandle.ToIntPtr(_self), out _subscription));
    }

    /// <summary>等待指定過濾器觸發的阻斷事件；超時返回 false。</summary>
    public async Task<bool> WaitForAsync(ulong filterId, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (true)
        {
            if (_filterIds.Contains(filterId))
            {
                return true;
            }

            var remaining = deadline - DateTime.UtcNow;
            if (remaining <= TimeSpan.Zero)
            {
                return false;
            }

            await _signal.WaitAsync(remaining);
        }
    }

    public void Dispose()
    {
        var subscription = Interlocked.Exchange(ref _subscription, 0);
        if (subscription != 0)
        {
            // 訂閱句柄屬於打開它的引擎會話；會話 Dispose 前先退訂
            FwpNative.FwpmNetEventUnsubscribe0(_engineHandle, subscription);
        }

        if (_self.IsAllocated)
        {
            _self.Free();
        }

        _signal.Dispose();
    }

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvStdcall)])]
    private static unsafe void OnEvent(nint context, FwpmNetEvent1* evt)
    {
        try
        {
            if (evt is null || evt->Type != FwpmNetEventType.ClassifyDrop || evt->Detail == 0
                || GCHandle.FromIntPtr(context).Target is not DropEventCollector collector)
            {
                return;
            }

            var drop = (FwpmNetEventClassifyDrop1*)evt->Detail;
            collector._filterIds.Enqueue(drop->FilterId);
            collector._signal.Release();
        }
        catch (Exception)
        {
            // 回調中的異常不能傳回本機代碼
        }
    }
}
