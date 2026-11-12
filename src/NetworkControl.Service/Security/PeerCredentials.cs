using System.IO.Pipes;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Security.Principal;

namespace NetworkControl.Service.Security;

/// <summary>IPC 對端進程的身份。</summary>
public sealed record PeerIdentity(int? Pid, string? UserId, bool IsAdmin);

/// <summary>
/// 從本地 IPC 連接中讀取對端進程憑據：
/// Windows 命名管道 → 客戶端 PID + 用戶 SID + 是否屬於 Administrators 組；
/// Linux SO_PEERCRED / macOS LOCAL_PEERCRED → PID + UID（root 視為管理員）。
/// </summary>
internal static partial class PeerCredentials
{
    private const string AdministratorsSid = "S-1-5-32-544";

    [SupportedOSPlatform("windows")]
    public static PeerIdentity FromNamedPipe(NamedPipeServerStream pipe)
    {
        int? pid = GetNamedPipeClientProcessId(pipe.SafePipeHandle, out var clientPid) ? (int)clientPid : null;
        string? sid = null;
        var isAdmin = false;

        try
        {
            // 客戶端以 Identification 級別連接：可以查詢令牌，但不能代表客戶端執行操作
            pipe.RunAsClient(() =>
            {
                using var identity = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
                sid = identity.User?.Value;
                // UAC 過濾令牌中 Administrators 組是「僅拒絕」，仍視為管理員帳戶（設計文檔 §14.1）
                isAdmin = identity.Groups?.Any(g => g.Value == AdministratorsSid) == true
                    || new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
            });
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // 無法識別用戶：只保留 PID
        }

        return new PeerIdentity(pid, sid, isAdmin);
    }

    public static PeerIdentity FromUnixSocket(Socket socket)
    {
        if (OperatingSystem.IsLinux())
        {
            // struct ucred { pid_t pid; uid_t uid; gid_t gid; }
            Span<byte> buffer = stackalloc byte[12];
            if (socket.GetRawSocketOption(SolSocket, SoPeerCred, buffer) >= 12)
            {
                var pid = MemoryMarshal.Read<int>(buffer);
                var uid = MemoryMarshal.Read<uint>(buffer[4..]);
                return new PeerIdentity(pid, uid.ToString(System.Globalization.CultureInfo.InvariantCulture), uid == 0);
            }
        }
        else if (OperatingSystem.IsMacOS())
        {
            Span<byte> pidBuffer = stackalloc byte[4];
            int? pid = socket.GetRawSocketOption(SolLocal, LocalPeerPid, pidBuffer) >= 4 ? MemoryMarshal.Read<int>(pidBuffer) : null;

            // struct xucred { u_int cr_version; uid_t cr_uid; short cr_ngroups; gid_t cr_groups[16]; }
            Span<byte> cred = stackalloc byte[76];
            if (socket.GetRawSocketOption(SolLocal, LocalPeerCred, cred) >= 8)
            {
                var uid = MemoryMarshal.Read<uint>(cred[4..]);
                return new PeerIdentity(pid, uid.ToString(System.Globalization.CultureInfo.InvariantCulture), uid == 0);
            }

            return new PeerIdentity(pid, null, false);
        }

        return new PeerIdentity(null, null, false);
    }

    private const int SolSocket = 1;
    private const int SoPeerCred = 17;
    private const int SolLocal = 0;
    private const int LocalPeerCred = 1;
    private const int LocalPeerPid = 2;

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    [SupportedOSPlatform("windows")]
    private static partial bool GetNamedPipeClientProcessId(Microsoft.Win32.SafeHandles.SafePipeHandle pipe, out uint clientProcessId);
}
