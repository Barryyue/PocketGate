using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace NetworkControl.Engine.Linux;

/// <summary>
/// libnetfilter_queue 與 libc 的 P/Invoke。簽名按頭文件 <c>libnetfilter_queue/libnetfilter_queue.h</c> 逐個核對：
/// 整數寬度必須一致（例如 <c>nfq_set_mode</c> 的 range 是 uint32_t）。
/// </summary>
internal static unsafe partial class NfqueueNative
{
    internal const string NetfilterQueue = "netfilter_queue";
    internal const string LibC = "libc";

    internal const byte CopyPacket = 2;          // NFQNL_COPY_PACKET
    internal const uint VerdictDrop = 0;         // NF_DROP
    internal const uint VerdictAccept = 1;       // NF_ACCEPT
    internal const uint QueueFlagFailOpen = 1;   // NFQA_CFG_F_FAIL_OPEN：內核隊列滿時放行而不是丟棄

    internal const int SolNetlink = 270;
    internal const int NetlinkNoEnobufs = 5;

    internal const short PollIn = 0x001;
    internal const int EIntr = 4;
    internal const int EAgain = 11;
    internal const int ENoBufs = 105;
    internal const int EfdCloexec = 0x80000;

    [StructLayout(LayoutKind.Sequential)]
    internal struct PollFd
    {
        public int Fd;
        public short Events;
        public short Revents;
    }

    private static int _resolverRegistered;

    /// <summary>
    /// 發行版的運行時包只提供帶版本號的 <c>libnetfilter_queue.so.1</c>（無版本的符號鏈接在 -dev 包裡）；
    /// <c>libc.so</c> 在 glibc 上是鏈接腳本而不是 ELF。這裡按實際文件名解析。
    /// </summary>
    internal static void EnsureResolver()
    {
        if (Interlocked.Exchange(ref _resolverRegistered, 1) == 0)
        {
            NativeLibrary.SetDllImportResolver(typeof(NfqueueNative).Assembly, Resolve);
        }
    }

    internal static IReadOnlyList<string> CandidateNames(string libraryName) => libraryName switch
    {
        NetfilterQueue => ["libnetfilter_queue.so.1", "libnetfilter_queue.so"],
        LibC => ["libc.so.6", "libc"],
        _ => [libraryName],
    };

    private static nint Resolve(string libraryName, Assembly assembly, DllImportSearchPath? searchPath)
    {
        foreach (var name in CandidateNames(libraryName))
        {
            if (NativeLibrary.TryLoad(name, assembly, searchPath, out var handle))
            {
                return handle;
            }
        }

        return 0;
    }

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial nint nfq_open();

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial int nfq_close(nint handle);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial int nfq_bind_pf(nint handle, ushort addressFamily);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial int nfq_unbind_pf(nint handle, ushort addressFamily);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial nint nfq_create_queue(
        nint handle,
        ushort queueNumber,
        delegate* unmanaged[Cdecl]<nint, nint, nint, nint, int> callback,
        nint data);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial int nfq_destroy_queue(nint queueHandle);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial int nfq_set_mode(nint queueHandle, byte mode, uint range);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial int nfq_set_queue_maxlen(nint queueHandle, uint length);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial int nfq_set_queue_flags(nint queueHandle, uint mask, uint flags);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial int nfq_fd(nint handle);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial int nfq_handle_packet(nint handle, byte* buffer, int length);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial nint nfq_get_msg_packet_hdr(nint packet);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial int nfq_get_payload(nint packet, out nint payload);

    [LibraryImport(NetfilterQueue)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial int nfq_set_verdict(nint queueHandle, uint packetId, uint verdict, uint payloadLength, nint payload);

    [LibraryImport(LibC, EntryPoint = "recv", SetLastError = true)]
    internal static partial nint Recv(int socket, byte* buffer, nuint length, int flags);

    [LibraryImport(LibC, EntryPoint = "poll", SetLastError = true)]
    internal static partial int Poll(PollFd* fds, nuint count, int timeout);

    [LibraryImport(LibC, EntryPoint = "eventfd", SetLastError = true)]
    internal static partial int EventFd(uint initial, int flags);

    [LibraryImport(LibC, EntryPoint = "write", SetLastError = true)]
    internal static partial nint Write(int fd, void* buffer, nuint count);

    [LibraryImport(LibC, EntryPoint = "close", SetLastError = true)]
    internal static partial int Close(int fd);

    [LibraryImport(LibC, EntryPoint = "setsockopt", SetLastError = true)]
    internal static partial int SetSockOpt(int socket, int level, int option, void* value, uint length);
}
