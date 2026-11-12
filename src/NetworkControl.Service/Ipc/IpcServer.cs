using System.IO.Pipes;
using System.Runtime.Versioning;
using System.Security.AccessControl;
using System.Security.Principal;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using NetworkControl.Ipc;

namespace NetworkControl.Service.Ipc;

/// <summary>配置 Kestrel 只監聽本地 IPC 端點（不開放任何 TCP 端口）。</summary>
internal static partial class IpcServer
{
    public static IpcEndpoint Configure(WebApplicationBuilder builder, ServiceOptions options)
    {
        var endpoint = IpcEndpoint.ResolveForServer(options.IpcEndpoint);

        if (endpoint.Transport == IpcTransport.NamedPipe && OperatingSystem.IsWindows())
        {
            builder.WebHost.UseNamedPipes(pipe =>
            {
                if (OperatingSystem.IsWindows() && Environment.IsPrivilegedProcess)
                {
                    // 以服務身份運行：允許本機交互用戶連接（授權由 LocalPeer 認證 + 策略控制）
                    pipe.CurrentUserOnly = false;
                    pipe.PipeSecurity = CreatePipeSecurity();
                }
            });
        }

        builder.WebHost.ConfigureKestrel(kestrel =>
        {
            void Http2(ListenOptions listen) => listen.Protocols = HttpProtocols.Http2;

            if (endpoint.Transport == IpcTransport.NamedPipe)
            {
                kestrel.ListenNamedPipe(endpoint.Address, Http2);
            }
            else
            {
                PrepareSocketPath(endpoint.Address);
                kestrel.ListenUnixSocket(endpoint.Address, Http2);
            }
        });

        return endpoint;
    }

    /// <summary>
    /// Unix socket 創建後設置權限：以 root 運行時允許本機所有用戶連接（身份和權限由 LocalPeer 認證與授權策略控制，
    /// 打包階段改為 root:netcontrol 0660）；以普通用戶運行（開發模式）時僅限當前用戶。
    /// </summary>
    public static void OnStarted(IpcEndpoint endpoint, ILogger logger)
    {
        LogListening(logger, endpoint);
        if (endpoint.Transport == IpcTransport.UnixSocket && !OperatingSystem.IsWindows() && File.Exists(endpoint.Address))
        {
            var mode = Environment.IsPrivilegedProcess
                ? UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.GroupRead | UnixFileMode.GroupWrite | UnixFileMode.OtherRead | UnixFileMode.OtherWrite
                : UnixFileMode.UserRead | UnixFileMode.UserWrite;
            File.SetUnixFileMode(endpoint.Address, mode);
        }
    }

    private static void PrepareSocketPath(string path)
    {
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        // 上次異常退出留下的 socket 文件會導致綁定失敗
        if (File.Exists(path))
        {
            File.Delete(path);
        }
    }

    [SupportedOSPlatform("windows")]
    private static PipeSecurity CreatePipeSecurity()
    {
        var security = new PipeSecurity();
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.InteractiveSid, null), PipeAccessRights.ReadWrite, AccessControlType.Allow));
        return security;
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "IPC 正在監聽 {Endpoint}")]
    private static partial void LogListening(ILogger logger, IpcEndpoint endpoint);
}
