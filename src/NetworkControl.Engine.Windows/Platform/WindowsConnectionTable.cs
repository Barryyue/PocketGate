using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;

namespace NetworkControl.Engine.Windows.Platform;

/// <summary>通過 iphlpapi 讀取 TCP/UDP 所有者 PID 表。</summary>
internal sealed partial class WindowsConnectionTable : IConnectionTable
{
    private const uint ErrorInsufficientBuffer = 122;
    private const int AfInet = 2;
    private const int AfInet6 = 23;
    private const int TcpTableOwnerPidAll = 5;
    private const int UdpTableOwnerPid = 1;
    private const int UdpTableOwnerPid6 = 8;
    private const int MaxAttempts = 3;

    public IReadOnlyList<ActiveConnection> Snapshot()
    {
        if (!OperatingSystem.IsWindows())
        {
            return [];
        }

        var result = new List<ActiveConnection>();
        ReadTable(GetExtendedTcpTable, AfInet, TcpTableOwnerPidAll, ParseTcp4, result);
        ReadTable(GetExtendedTcpTable, AfInet6, TcpTableOwnerPidAll, ParseTcp6, result);
        ReadTable(GetExtendedUdpTable, AfInet, UdpTableOwnerPid, ParseUdp4, result);
        ReadTable(GetExtendedUdpTable, AfInet6, UdpTableOwnerPid6, ParseUdp6, result);
        return result;
    }

    private delegate uint TableGetter(IntPtr table, ref int size, bool order, int family, int tableClass, uint reserved);

    private static void ReadTable(TableGetter getter, int family, int tableClass, Func<IntPtr, int, List<ActiveConnection>> parser, List<ActiveConnection> destination)
    {
        var size = 0;
        for (var attempt = 0; attempt < MaxAttempts; attempt++)
        {
            var error = getter(IntPtr.Zero, ref size, true, family, tableClass, 0);
            if (error != ErrorInsufficientBuffer || size <= 0)
            {
                return;
            }

            var buffer = Marshal.AllocHGlobal(size);
            try
            {
                error = getter(buffer, ref size, true, family, tableClass, 0);
                if (error == 0)
                {
                    destination.AddRange(parser(buffer, size));
                    return;
                }

                if (error != ErrorInsufficientBuffer)
                {
                    return;
                }
            }
            finally
            {
                Marshal.FreeHGlobal(buffer);
            }
        }
    }

    private static List<ActiveConnection> ParseTcp4(IntPtr buffer, int _)
    {
        var count = Marshal.ReadInt32(buffer);
        var result = new List<ActiveConnection>(Math.Max(0, count));
        var offset = sizeof(uint);
        var size = Marshal.SizeOf<TcpRow4>();
        for (var i = 0; i < count; i++)
        {
            var row = Marshal.PtrToStructure<TcpRow4>(IntPtr.Add(buffer, offset));
            result.Add(new ActiveConnection(
                L4Protocol.Tcp,
                new IPEndPoint(new IPAddress(row.LocalAddress), ToPort(row.LocalPort)),
                new IPEndPoint(new IPAddress(row.RemoteAddress), ToPort(row.RemotePort)),
                MapState(row.State),
                checked((int)row.ProcessId)));
            offset += size;
        }

        return result;
    }

    private static List<ActiveConnection> ParseTcp6(IntPtr buffer, int _)
    {
        var count = Marshal.ReadInt32(buffer);
        var result = new List<ActiveConnection>(Math.Max(0, count));
        var offset = sizeof(uint);
        var size = Marshal.SizeOf<TcpRow6>();
        for (var i = 0; i < count; i++)
        {
            var row = Marshal.PtrToStructure<TcpRow6>(IntPtr.Add(buffer, offset));
            result.Add(new ActiveConnection(
                L4Protocol.Tcp,
                new IPEndPoint(new IPAddress(row.LocalAddress, row.LocalScopeId), ToPort(row.LocalPort)),
                new IPEndPoint(new IPAddress(row.RemoteAddress, row.RemoteScopeId), ToPort(row.RemotePort)),
                MapState(row.State),
                checked((int)row.ProcessId)));
            offset += size;
        }

        return result;
    }

    private static List<ActiveConnection> ParseUdp4(IntPtr buffer, int _)
    {
        var count = Marshal.ReadInt32(buffer);
        var result = new List<ActiveConnection>(Math.Max(0, count));
        var offset = sizeof(uint);
        var size = Marshal.SizeOf<UdpRow4>();
        for (var i = 0; i < count; i++)
        {
            var row = Marshal.PtrToStructure<UdpRow4>(IntPtr.Add(buffer, offset));
            result.Add(new ActiveConnection(L4Protocol.Udp, new IPEndPoint(new IPAddress(row.LocalAddress), ToPort(row.LocalPort)), null, ConnectionState.Udp, checked((int)row.ProcessId)));
            offset += size;
        }

        return result;
    }

    private static List<ActiveConnection> ParseUdp6(IntPtr buffer, int _)
    {
        var count = Marshal.ReadInt32(buffer);
        var result = new List<ActiveConnection>(Math.Max(0, count));
        var offset = sizeof(uint);
        var size = Marshal.SizeOf<UdpRow6>();
        for (var i = 0; i < count; i++)
        {
            var row = Marshal.PtrToStructure<UdpRow6>(IntPtr.Add(buffer, offset));
            result.Add(new ActiveConnection(L4Protocol.Udp, new IPEndPoint(new IPAddress(row.LocalAddress, row.LocalScopeId), ToPort(row.LocalPort)), null, ConnectionState.Udp, checked((int)row.ProcessId)));
            offset += size;
        }

        return result;
    }

    internal static int ToPort(uint value) => BinaryPrimitives.ReverseEndianness((ushort)(value & ushort.MaxValue));

    internal static ConnectionState MapState(uint state) => state switch
    {
        2 => ConnectionState.Listen,
        3 => ConnectionState.SynSent,
        4 => ConnectionState.SynReceived,
        5 => ConnectionState.Established,
        6 or 7 or 8 or 9 => ConnectionState.Closing,
        11 => ConnectionState.TimeWait,
        _ => ConnectionState.Closed,
    };

    [LibraryImport("iphlpapi.dll", EntryPoint = "GetExtendedTcpTable")]
    private static partial uint GetExtendedTcpTable(IntPtr table, ref int size, [MarshalAs(UnmanagedType.Bool)] bool order, int family, int tableClass, uint reserved);

    [LibraryImport("iphlpapi.dll", EntryPoint = "GetExtendedUdpTable")]
    private static partial uint GetExtendedUdpTable(IntPtr table, ref int size, [MarshalAs(UnmanagedType.Bool)] bool order, int family, int tableClass, uint reserved);

    [StructLayout(LayoutKind.Sequential)]
    internal struct TcpRow4
    {
        public uint State;
        public uint LocalAddress;
        public uint LocalPort;
        public uint RemoteAddress;
        public uint RemotePort;
        public uint ProcessId;
    }

    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    internal struct TcpRow6
    {
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)] public byte[] LocalAddress;
        public uint LocalScopeId;
        public uint LocalPort;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)] public byte[] RemoteAddress;
        public uint RemoteScopeId;
        public uint RemotePort;
        public uint State;
        public uint ProcessId;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct UdpRow4
    {
        public uint LocalAddress;
        public uint LocalPort;
        public uint ProcessId;
    }

    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    internal struct UdpRow6
    {
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)] public byte[] LocalAddress;
        public uint LocalScopeId;
        public uint LocalPort;
        public uint ProcessId;
    }
}
