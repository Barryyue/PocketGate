using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;

namespace NetworkControl.Engine.Linux;

/// <summary>
/// NFQUEUE 報文的判決邏輯（不含原生調用，便於測試）。
/// </summary>
/// <remarks>
/// <see cref="IEngineHost.DecideAsync"/> 可能要等用戶回答彈窗才完成。如果在收包線程上同步等待，
/// 隊列中所有後續報文（包括 DNS）都會被卡住，等於全機斷網。因此：已經完成的判決（規則 / 緩存命中）立即下發，
/// 未完成的掛起，完成後再從線程池下發；libnetfilter_queue 允許判決亂序。
/// </remarks>
internal sealed class NfqueuePacketHandler(IEngineHost host, Action<uint, bool> setVerdict, TimeProvider time, int maxPending = NfqueuePacketHandler.DefaultMaxPending)
{
    public const int DefaultMaxPending = 4096;

    private long _nextFlowId;
    private int _pending;

    /// <summary>正在等待判決的報文數。</summary>
    public int PendingCount => Volatile.Read(ref _pending);

    /// <summary>無法解析或無法判決時的處理方式（失效策略）。</summary>
    private bool FallbackAccept => host.FailPolicy != FailPolicy.FailClosed;

    public void Handle(uint packetId, byte hook, ReadOnlySpan<byte> payload)
    {
        if (!NfqueuePacketParser.TryParse(payload, hook, out var parsed))
        {
            // 包括非首個 IP 分片和無法識別的 IPv6 擴展頭：不能一律放行，否則加一個擴展頭就能繞過規則
            setVerdict(packetId, FallbackAccept);
            return;
        }

        if (Interlocked.Increment(ref _pending) > maxPending)
        {
            Interlocked.Decrement(ref _pending);
            setVerdict(packetId, FallbackAccept);
            return;
        }

        var flow = new FlowInfo
        {
            FlowId = (ulong)Interlocked.Increment(ref _nextFlowId),
            Direction = parsed.Direction,
            Protocol = parsed.Protocol,
            Local = parsed.Local,
            Remote = parsed.Remote,
            Timestamp = time.GetUtcNow(),
            Origin = FlowOrigin.Pending,
        };

        ValueTask<VerdictAction> decision;
        try
        {
            decision = host.DecideAsync(flow);
        }
        catch (Exception)
        {
            Complete(packetId, FallbackAccept);
            return;
        }

        if (decision.IsCompletedSuccessfully)
        {
            Complete(packetId, decision.Result == VerdictAction.Allow);
            return;
        }

        _ = CompleteLaterAsync(packetId, decision);
    }

    private async Task CompleteLaterAsync(uint packetId, ValueTask<VerdictAction> decision)
    {
        bool accept;
        try
        {
            accept = await decision.ConfigureAwait(false) == VerdictAction.Allow;
        }
        catch (Exception)
        {
            // 服務停止（ApplicationStopping）或管線出錯：按失效策略處理
            accept = FallbackAccept;
        }

        Complete(packetId, accept);
    }

    private void Complete(uint packetId, bool accept)
    {
        Interlocked.Decrement(ref _pending);
        try
        {
            setVerdict(packetId, accept);
        }
        catch (Exception)
        {
            // 隊列已經關閉：內核會在隊列銷毀時處理剩餘報文
        }
    }
}
