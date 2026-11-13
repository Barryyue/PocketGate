using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;

namespace NetworkControl.Engine.Linux;

/// <summary>Linux NFQUEUE 引擎：nftables 把新連接送入隊列，libnetfilter_queue 掛起報文等待判決。</summary>
/// <remarks>
/// M2 進行中：還不能提供 PID（需要 eBPF 進程歸屬），也不採集 DNS，因此應用規則和域名規則暫時匹配不到；
/// 在這兩項完成前服務不會在 Auto 下選擇本引擎。
/// </remarks>
internal sealed class LinuxNetworkEngine(NftablesController nftables, NfqueueAdapter queue) : INetworkEngine
{
    public string Name => "NfQueue";

    public EngineCapabilities Capabilities => EngineCapabilities.PendingVerdict | EngineCapabilities.Inbound;

    public async Task StartAsync(IEngineHost host, CancellationToken cancellationToken)
    {
        // 先綁定隊列再下發規則：反過來的話，中間這段時間 fail-closed 會丟包、fail-open 會漏放
        await queue.StartAsync(host, cancellationToken);
        try
        {
            await nftables.ApplyAsync(host.FailPolicy != FailPolicy.FailClosed, cancellationToken);
        }
        catch
        {
            await queue.StopAsync(CancellationToken.None);
            throw;
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        // 先撤規則（不再有新報文入隊），再關閉隊列
        await nftables.RemoveAsync(cancellationToken);
        await queue.StopAsync(cancellationToken);
    }

    public ValueTask DisposeAsync() => queue.DisposeAsync();
}
