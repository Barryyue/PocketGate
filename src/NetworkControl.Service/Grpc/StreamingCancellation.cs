using Grpc.Core;

namespace NetworkControl.Service.Grpc;

/// <summary>
/// 流式接口（狀態、彈窗、規則變更、實時日誌）只在客戶端斷開時才會結束；服務停止時必須主動結束它們，
/// 否則 Kestrel 的優雅關閉會一直等待這些請求，直到關閉超時。
/// </summary>
internal static class StreamingCancellation
{
    /// <summary>客戶端斷開或服務開始停止時取消。</summary>
    public static CancellationTokenSource Link(ServerCallContext context)
    {
        var lifetime = context.GetHttpContext().RequestServices.GetRequiredService<IHostApplicationLifetime>();
        return CancellationTokenSource.CreateLinkedTokenSource(context.CancellationToken, lifetime.ApplicationStopping);
    }
}
