using System.Runtime.InteropServices;
using global::Android.OS;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Android;

/// <summary>
/// 讀取虛擬網卡的專用線程。文件描述符保持非阻塞，用 poll 帶超時等待，
/// 這樣停止時不需要等下一個報文到達（在另一個線程上關閉文件描述符不能可靠地喚醒阻塞中的 read）。
/// 同時作為 <see cref="IPacketWriter"/>，把假 DNS 的回應寫回虛擬網卡。
/// </summary>
internal sealed partial class TunReader : IDisposable, IPacketWriter
{
    private const short PollIn = 0x0001;
    private const int PollTimeoutMs = 250;
    private const int Eintr = 4;
    private const int Eagain = 11;

    private readonly ParcelFileDescriptor _descriptor;
    private readonly IPacketSink _sink;
    private readonly int _mtu;
    private readonly Action<Exception> _onError;
    private readonly Thread _thread;
    private volatile bool _stopping;

    public TunReader(ParcelFileDescriptor descriptor, IPacketSink sink, int mtu, Action<Exception> onError)
    {
        _descriptor = descriptor;
        _sink = sink;
        _mtu = mtu;
        _onError = onError;
        _thread = new Thread(Run) { IsBackground = true, Name = "NetworkControl.TunReader" };
        _thread.Start();
    }

    /// <summary>停止讀線程並關閉文件描述符。</summary>
    public void Dispose()
    {
        _stopping = true;
        if (Thread.CurrentThread != _thread)
        {
            _thread.Join(TimeSpan.FromSeconds(2));
        }

        try
        {
            _descriptor.Close();
        }
        catch (Java.IO.IOException)
        {
            // 已關閉
        }

        _descriptor.Dispose();
    }

    public void Write(ReadOnlySpan<byte> packet)
    {
        if (_stopping)
        {
            return;
        }

        // 虛擬網卡按報文寫入：一次 write 就是一個完整的報文；非阻塞模式下隊列滿時丟棄（DNS 客戶端會重試）
        var written = WriteFd(_descriptor.Fd, packet, packet.Length);
        if (written < 0 && Marshal.GetLastPInvokeError() is not (Eagain or Eintr))
        {
            throw new IOException($"寫入虛擬網卡失敗（errno {Marshal.GetLastPInvokeError()}）。");
        }
    }

    private void Run()
    {
        var fd = _descriptor.Fd;
        var buffer = new byte[Math.Max(_mtu, 1500) + 64];
        var poll = new PollFd { Fd = fd, Events = PollIn };
        try
        {
            while (!_stopping)
            {
                poll.Revents = 0;
                var ready = Poll(ref poll, 1, PollTimeoutMs);
                if (ready < 0)
                {
                    var errno = Marshal.GetLastPInvokeError();
                    if (errno == Eintr)
                    {
                        continue;
                    }

                    throw new IOException($"poll 失敗（errno {errno}）。");
                }

                if (ready == 0 || (poll.Revents & PollIn) == 0)
                {
                    continue;
                }

                // 一次 poll 之後盡量讀完已到達的報文
                while (!_stopping)
                {
                    var read = Read(fd, buffer, buffer.Length);
                    if (read > 0)
                    {
                        _sink.OnPacket(buffer.AsSpan(0, (int)read), this);
                        continue;
                    }

                    if (read < 0)
                    {
                        var errno = Marshal.GetLastPInvokeError();
                        if (errno is Eagain or Eintr)
                        {
                            break;
                        }

                        throw new IOException($"讀取虛擬網卡失敗（errno {errno}）。");
                    }

                    break;
                }
            }
        }
        catch (Exception ex) when (!_stopping)
        {
            _onError(ex);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PollFd
    {
        public int Fd;
        public short Events;
        public short Revents;
    }

    [LibraryImport("libc", EntryPoint = "poll", SetLastError = true)]
    private static partial int Poll(ref PollFd fds, nuint count, int timeout);

    [LibraryImport("libc", EntryPoint = "write", SetLastError = true)]
    private static partial nint WriteFd(int fd, ReadOnlySpan<byte> buffer, nint count);

    [LibraryImport("libc", EntryPoint = "read", SetLastError = true)]
    private static partial nint Read(int fd, [Out] byte[] buffer, nint count);
}
