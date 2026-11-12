using System.IO.Pipes;
using System.Net.Sockets;
using System.Security.Principal;
using Grpc.Net.Client;

namespace NetworkControl.Ipc;

/// <summary>創建連接到本地服務的 gRPC 通道（命名管道 / Unix domain socket 上的 HTTP/2）。</summary>
public static class IpcChannelFactory
{
    public static GrpcChannel Create(IpcEndpoint endpoint)
    {
        var handler = new SocketsHttpHandler
        {
            ConnectCallback = (_, cancellationToken) => ConnectAsync(endpoint, cancellationToken),
            // 本地 IPC 絕不能走代理：系統代理（如調試代理工具）會讓 HTTP/2 明文連接協商失敗
            UseProxy = false,
            // 長連接上的流式訂閱需要保活，及時發現服務重啟
            KeepAlivePingDelay = TimeSpan.FromSeconds(20),
            KeepAlivePingTimeout = TimeSpan.FromSeconds(10),
            KeepAlivePingPolicy = HttpKeepAlivePingPolicy.WithActiveRequests,
            EnableMultipleHttp2Connections = true,
        };

        return GrpcChannel.ForAddress("http://localhost", new GrpcChannelOptions
        {
            HttpHandler = handler,
            DisposeHttpClient = true,
            MaxReceiveMessageSize = 16 * 1024 * 1024,
        });
    }

    private static async ValueTask<Stream> ConnectAsync(IpcEndpoint endpoint, CancellationToken cancellationToken)
    {
        if (endpoint.Transport == IpcTransport.NamedPipe)
        {
            // Identification 級別：允許服務端識別客戶端身份，但不能冒充客戶端執行操作
            var pipe = new NamedPipeClientStream(
                ".",
                endpoint.Address,
                PipeDirection.InOut,
                PipeOptions.Asynchronous | PipeOptions.WriteThrough,
                TokenImpersonationLevel.Identification);
            try
            {
                await pipe.ConnectAsync(TimeSpan.FromSeconds(3), cancellationToken);
                return pipe;
            }
            catch
            {
                await pipe.DisposeAsync();
                throw;
            }
        }

        var socket = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
        try
        {
            await socket.ConnectAsync(new UnixDomainSocketEndPoint(endpoint.Address), cancellationToken);
            return new NetworkStream(socket, ownsSocket: true);
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }
}
