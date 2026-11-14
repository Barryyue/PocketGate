using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;

namespace NetworkControl.Engine.Windows.Platform;

internal static partial class WindowsConnectionKiller
{
    public static bool CanKill(ConnectionKey key) =>
        key.Protocol == L4Protocol.Tcp
        && key.Local.AddressFamily == AddressFamily.InterNetwork
        && key.Remote.AddressFamily == AddressFamily.InterNetwork;

    public static ValueTask<bool> KillAsync(ConnectionKey key, CancellationToken cancellationToken)
    {
        if (!OperatingSystem.IsWindows() || !CanKill(key))
        {
            return ValueTask.FromResult(false);
        }

        return new ValueTask<bool>(Task.Run(() =>
        {
            cancellationToken.ThrowIfCancellationRequested();
            var row = new MibTcpRow
            {
                State = 12,
                LocalAddress = ToAddress(key.Local.Address),
                LocalPort = ToPort(key.Local.Port),
                RemoteAddress = ToAddress(key.Remote.Address),
                RemotePort = ToPort(key.Remote.Port),
            };
            return SetTcpEntry(ref row) == 0;
        }, cancellationToken));
    }

    internal static uint ToAddress(IPAddress address) => BitConverter.ToUInt32(address.GetAddressBytes(), 0);

    internal static uint ToPort(int port) => BinaryPrimitives.ReverseEndianness((ushort)port);

    [LibraryImport("iphlpapi.dll", EntryPoint = "SetTcpEntry")]
    private static partial uint SetTcpEntry(ref MibTcpRow row);

    [StructLayout(LayoutKind.Sequential)]
    private struct MibTcpRow
    {
        public uint State;
        public uint LocalAddress;
        public uint LocalPort;
        public uint RemoteAddress;
        public uint RemotePort;
    }
}
