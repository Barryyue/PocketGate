using System.Buffers.Binary;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;

namespace NetworkControl.Engine.Linux;

/// <summary>libnetfilter_queue 的單隊列適配器。</summary>
/// <remarks>
/// 線程模型：專用收包線程用 poll 同時等待 netlink 套接字和停止用的 eventfd，回調在該線程上執行；
/// 判決可能來自線程池（彈窗回答之後），所以所有對 nfq 句柄的調用都在 <see cref="_nativeLock"/> 內進行。
/// 停止時先喚醒並等待收包線程退出，再釋放原生資源，避免收包線程使用已釋放的句柄。
/// </remarks>
internal sealed partial class NfqueueAdapter(TimeProvider time, ILogger<NfqueueAdapter> logger) : IAsyncDisposable
{
    internal const ushort QueueNumber = 0;

    private const ushort AfInet = 2;
    private const ushort AfInet6 = 10;
    private const uint CopyRange = 512;
    private const uint KernelQueueLength = 8192;
    private static readonly TimeSpan StopTimeout = TimeSpan.FromSeconds(5);

    private readonly Lock _nativeLock = new();
    private nint _handle;
    private nint _queue;
    private int _eventFd = -1;
    private GCHandle _self;
    private Thread? _thread;
    private TaskCompletionSource? _threadExited;
    private NfqueuePacketHandler? _handler;
    private IEngineHost? _host;
    private long _overflows;

    public long Overflows => Interlocked.Read(ref _overflows);

    public unsafe Task StartAsync(IEngineHost host, CancellationToken cancellationToken)
    {
        if (!OperatingSystem.IsLinux())
        {
            throw new PlatformNotSupportedException("NFQUEUE 只能在 Linux 上運行。");
        }

        if (_thread is not null)
        {
            throw new InvalidOperationException("NFQUEUE 已經啟動。");
        }

        NfqueueNative.EnsureResolver();
        _host = host;
        _handler = new NfqueuePacketHandler(host, SetVerdict, time);
        try
        {
            _handle = NfqueueNative.nfq_open();
            if (_handle == 0)
            {
                throw new InvalidOperationException("無法打開 libnetfilter_queue（需要 root 或 CAP_NET_ADMIN）。");
            }

            // 內核 3.8 起 bind/unbind_pf 已無作用，舊內核上仍需要
            Bind(AfInet);
            Bind(AfInet6);

            _self = GCHandle.Alloc(this);
            _queue = NfqueueNative.nfq_create_queue(_handle, QueueNumber, &OnPacketNative, GCHandle.ToIntPtr(_self));
            if (_queue == 0)
            {
                throw new InvalidOperationException($"無法創建 NFQUEUE {QueueNumber}（可能已被其他程序佔用）。");
            }

            Check(NfqueueNative.nfq_set_mode(_queue, NfqueueNative.CopyPacket, CopyRange), "設置 NFQUEUE 複製模式");
            Check(NfqueueNative.nfq_set_queue_maxlen(_queue, KernelQueueLength), "設置 NFQUEUE 長度");
            var failOpen = host.FailPolicy != FailPolicy.FailClosed;
            if (NfqueueNative.nfq_set_queue_flags(_queue, NfqueueNative.QueueFlagFailOpen, failOpen ? NfqueueNative.QueueFlagFailOpen : 0) < 0)
            {
                LogQueueFlagsUnsupported(logger);
            }

            var fd = NfqueueNative.nfq_fd(_handle);
            if (fd < 0)
            {
                throw new InvalidOperationException("無法取得 NFQUEUE 文件描述符。");
            }

            // 隊列溢出時不要讓 recv 返回 ENOBUFS 中斷收包，溢出由內核按 FAIL_OPEN 標誌處理
            var one = 1;
            _ = NfqueueNative.SetSockOpt(fd, NfqueueNative.SolNetlink, NfqueueNative.NetlinkNoEnobufs, &one, sizeof(int));

            _eventFd = NfqueueNative.EventFd(0, NfqueueNative.EfdCloexec);
            if (_eventFd < 0)
            {
                throw new InvalidOperationException($"eventfd 失敗：errno {Marshal.GetLastPInvokeError()}");
            }

            _threadExited = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            _thread = new Thread(() => ReceiveLoop(fd)) { IsBackground = true, Name = "NFQUEUE receive" };
            _thread.Start();
            return Task.CompletedTask;
        }
        catch
        {
            ReleaseNative();
            throw;
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        if (_thread is null)
        {
            ReleaseNative();
            return;
        }

        Signal();
        try
        {
            await _threadExited!.Task.WaitAsync(StopTimeout, cancellationToken);
        }
        catch (TimeoutException)
        {
            // 收包線程沒有退出：寧可洩漏原生句柄，也不能在它還可能使用時釋放
            LogStopTimeout(logger);
            return;
        }

        _thread = null;
        ReleaseNative();
    }

    public async ValueTask DisposeAsync() => await StopAsync(CancellationToken.None);

    private unsafe void ReceiveLoop(int fd)
    {
        var buffer = new byte[256 * 1024];
        var fds = stackalloc NfqueueNative.PollFd[2];
        fds[0] = new NfqueueNative.PollFd { Fd = fd, Events = NfqueueNative.PollIn };
        fds[1] = new NfqueueNative.PollFd { Fd = _eventFd, Events = NfqueueNative.PollIn };
        string? fatal = null;
        try
        {
            fixed (byte* pointer = buffer)
            {
                while (true)
                {
                    fds[0].Revents = 0;
                    fds[1].Revents = 0;
                    if (NfqueueNative.Poll(fds, 2, -1) < 0)
                    {
                        var errno = Marshal.GetLastPInvokeError();
                        if (errno == NfqueueNative.EIntr)
                        {
                            continue;
                        }

                        fatal = $"poll 失敗：errno {errno}";
                        break;
                    }

                    if (fds[1].Revents != 0)
                    {
                        break; // 停止信號
                    }

                    if (fds[0].Revents == 0)
                    {
                        continue;
                    }

                    var length = NfqueueNative.Recv(fd, pointer, (nuint)buffer.Length, 0);
                    if (length < 0)
                    {
                        var errno = Marshal.GetLastPInvokeError();
                        if (errno is NfqueueNative.EIntr or NfqueueNative.EAgain)
                        {
                            continue;
                        }

                        if (errno == NfqueueNative.ENoBufs)
                        {
                            // 用戶態處理不過來，內核丟棄了部分隊列消息；繼續收包
                            if (Interlocked.Increment(ref _overflows) % 1000 == 1)
                            {
                                LogOverflow(logger, Overflows);
                            }

                            continue;
                        }

                        fatal = $"recv 失敗：errno {errno}";
                        break;
                    }

                    if (length == 0)
                    {
                        fatal = "netlink 套接字已關閉";
                        break;
                    }

                    lock (_nativeLock)
                    {
                        _ = NfqueueNative.nfq_handle_packet(_handle, pointer, checked((int)length));
                    }
                }
            }
        }
        catch (Exception ex)
        {
            fatal = ex.Message;
        }
        finally
        {
            if (fatal is not null)
            {
                // 銷毀隊列：fail-open 時 nftables 的 bypass 才會生效，否則內核會一直排隊直到丟包
                lock (_nativeLock)
                {
                    DestroyQueue();
                }

                LogReceiveFailed(logger, fatal);
                _host?.OnEngineFault(new EngineFault("nfqueue.receive", fatal, IsFatal: true));
            }

            _threadExited!.TrySetResult();
        }
    }

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvCdecl)])]
    private static int OnPacketNative(nint queue, nint message, nint packet, nint data)
    {
        // 異常不能穿過原生回調邊界，否則進程直接崩潰
        try
        {
            if (GCHandle.FromIntPtr(data).Target is NfqueueAdapter adapter)
            {
                adapter.OnPacket(packet);
            }
        }
        catch (Exception)
        {
        }

        return 0;
    }

    private unsafe void OnPacket(nint packet)
    {
        var header = NfqueueNative.nfq_get_msg_packet_hdr(packet);
        if (header == 0)
        {
            return; // 拿不到報文 ID 就無法下發判決
        }

        // struct nfqnl_msg_packet_hdr { __be32 packet_id; __be16 hw_protocol; __u8 hook; }
        var headerBytes = new ReadOnlySpan<byte>((void*)header, 7);
        var packetId = BinaryPrimitives.ReadUInt32BigEndian(headerBytes);
        var hook = headerBytes[6];
        var handler = _handler;
        if (handler is null)
        {
            SetVerdict(packetId, accept: true);
            return;
        }

        var length = NfqueueNative.nfq_get_payload(packet, out var payload);
        var bytes = length > 0 && payload != 0 ? new ReadOnlySpan<byte>((void*)payload, length) : [];
        handler.Handle(packetId, hook, bytes);
    }

    private void SetVerdict(uint packetId, bool accept)
    {
        lock (_nativeLock)
        {
            if (_queue != 0)
            {
                _ = NfqueueNative.nfq_set_verdict(_queue, packetId, accept ? NfqueueNative.VerdictAccept : NfqueueNative.VerdictDrop, 0, 0);
            }
        }
    }

    private unsafe void Signal()
    {
        if (_eventFd >= 0)
        {
            ulong one = 1;
            _ = NfqueueNative.Write(_eventFd, &one, sizeof(ulong));
        }
    }

    private void Bind(ushort family)
    {
        _ = NfqueueNative.nfq_unbind_pf(_handle, family);
        Check(NfqueueNative.nfq_bind_pf(_handle, family), $"綁定協議族 {family}");
    }

    private static void Check(int result, string operation)
    {
        if (result < 0)
        {
            throw new InvalidOperationException($"{operation}失敗：{result}");
        }
    }

    private void DestroyQueue()
    {
        if (_queue != 0)
        {
            _ = NfqueueNative.nfq_destroy_queue(_queue);
            _queue = 0;
        }
    }

    /// <summary>可重複調用（DI 會對同一實例多次 DisposeAsync）。</summary>
    private void ReleaseNative()
    {
        lock (_nativeLock)
        {
            DestroyQueue();
            if (_handle != 0)
            {
                _ = NfqueueNative.nfq_close(_handle);
                _handle = 0;
            }
        }

        if (_eventFd >= 0)
        {
            _ = NfqueueNative.Close(_eventFd);
            _eventFd = -1;
        }

        if (_self.IsAllocated)
        {
            _self.Free();
        }

        _handler = null;
        _host = null;
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "內核不支持 NFQUEUE 隊列標誌（FAIL_OPEN），隊列滿時將丟包")]
    private static partial void LogQueueFlagsUnsupported(ILogger logger);

    [LoggerMessage(Level = LogLevel.Warning, Message = "NFQUEUE 隊列溢出，累計 {Count} 次")]
    private static partial void LogOverflow(ILogger logger, long count);

    [LoggerMessage(Level = LogLevel.Critical, Message = "NFQUEUE 收包失敗：{Reason}")]
    private static partial void LogReceiveFailed(ILogger logger, string reason);

    [LoggerMessage(Level = LogLevel.Error, Message = "NFQUEUE 收包線程在超時內沒有退出，原生句柄將不會釋放")]
    private static partial void LogStopTimeout(ILogger logger);
}
