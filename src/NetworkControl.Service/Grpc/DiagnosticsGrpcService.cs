using Google.Protobuf;
using Grpc.Core;
using Microsoft.AspNetCore.Authorization;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Service.Diagnostics;
using NetworkControl.Service.Security;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

/// <summary>
/// 診斷包導出。診斷包包含所有用戶的日誌和配置，所以需要管理員權限。
/// 先完整生成到臨時文件（關閉時自動刪除），再分塊返回：收集過程中的錯誤不會讓客戶端收到半個 zip。
/// </summary>
[Authorize(Policy = IpcPolicies.Admin)]
internal sealed class DiagnosticsGrpcService(ServiceDiagnosticsBuilder builder) : Proto.Diagnostics.DiagnosticsBase
{
    private const int ChunkSize = 64 * 1024;

    public override async Task Export(Proto.ExportDiagnosticsRequest request, IServerStreamWriter<Proto.DiagnosticsChunk> responseStream, ServerCallContext context)
    {
        using var streaming = StreamingCancellation.Link(context);
        var ct = streaming.Token;

        var path = Path.Combine(Path.GetTempPath(), $"networkcontrol-diagnostics-{Guid.NewGuid():N}.zip");
        await using var file = new FileStream(path, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None, ChunkSize, FileOptions.DeleteOnClose | FileOptions.Asynchronous);
        await builder.BuildAsync(file, new DiagnosticsCollectOptions
        {
            IncludeConfiguration = request.IncludeConfiguration,
            IncludeActivity = request.IncludeActivity,
        }, ct);

        file.Position = 0;
        var buffer = new byte[ChunkSize];
        int read;
        while ((read = await file.ReadAsync(buffer, ct)) > 0)
        {
            await responseStream.WriteAsync(new Proto.DiagnosticsChunk { Data = ByteString.CopyFrom(buffer, 0, read) }, ct);
        }
    }
}
