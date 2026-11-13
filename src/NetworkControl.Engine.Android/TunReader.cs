using System.Runtime.InteropServices;
using global::Android.OS;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Android;

/// <summary>
/// 讀取虛擬網卡的專用線程。文件描述符保持非阻塞，用 poll 帶超時等待，
/// 這樣停止時不需要等下一個報文到達（在另一個線程上關閉文件描述符不能可靠地喚醒阻塞中的 read）。
/// </summary>
internal sealed partial class TunReader : IDisposable
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
                        _sink.OnPacket(buffer.AsSpan(0, (int)read));
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

    [LibraryImport("libc", EntryPoint = "read", SetLastError = true)]
    private static partial nint Read(int fd, [Out] byte[] buffer, nint count);
}
