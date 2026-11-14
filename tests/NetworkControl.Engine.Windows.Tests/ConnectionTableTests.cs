using System.Net;
using System.Runtime.InteropServices;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;
using NetworkControl.Engine.Windows.Platform;

namespace NetworkControl.Engine.Windows.Tests;

public sealed class ConnectionTableTests
{
    [Theory]
    [InlineData(typeof(WindowsConnectionTable.TcpRow4), 24)]
    [InlineData(typeof(WindowsConnectionTable.TcpRow6), 56)]
    [InlineData(typeof(WindowsConnectionTable.UdpRow4), 12)]
    [InlineData(typeof(WindowsConnectionTable.UdpRow6), 28)]
    public void Row_sizes_match_sdk(Type type, int expected) =>
        Assert.Equal(expected, Marshal.SizeOf(type));

    [Theory]
    [InlineData(nameof(WindowsConnectionTable.TcpRow4.State), 0)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow4.LocalAddress), 4)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow4.LocalPort), 8)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow4.RemoteAddress), 12)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow4.RemotePort), 16)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow4.ProcessId), 20)]
    public void Tcp4_offsets_match_sdk(string field, int expected) =>
        Assert.Equal(expected, Marshal.OffsetOf<WindowsConnectionTable.TcpRow4>(field).ToInt32());

    [Theory]
    [InlineData(nameof(WindowsConnectionTable.TcpRow6.LocalAddress), 0)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow6.LocalScopeId), 16)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow6.LocalPort), 20)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow6.RemoteAddress), 24)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow6.RemoteScopeId), 40)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow6.RemotePort), 44)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow6.State), 48)]
    [InlineData(nameof(WindowsConnectionTable.TcpRow6.ProcessId), 52)]
    public void Tcp6_offsets_match_sdk(string field, int expected) =>
        Assert.Equal(expected, Marshal.OffsetOf<WindowsConnectionTable.TcpRow6>(field).ToInt32());

    [Theory]
    [InlineData(nameof(WindowsConnectionTable.UdpRow4.LocalAddress), 0)]
    [InlineData(nameof(WindowsConnectionTable.UdpRow4.LocalPort), 4)]
    [InlineData(nameof(WindowsConnectionTable.UdpRow4.ProcessId), 8)]
    public void Udp4_offsets_match_sdk(string field, int expected) =>
        Assert.Equal(expected, Marshal.OffsetOf<WindowsConnectionTable.UdpRow4>(field).ToInt32());

    [Theory]
    [InlineData(nameof(WindowsConnectionTable.UdpRow6.LocalAddress), 0)]
    [InlineData(nameof(WindowsConnectionTable.UdpRow6.LocalScopeId), 16)]
    [InlineData(nameof(WindowsConnectionTable.UdpRow6.LocalPort), 20)]
    [InlineData(nameof(WindowsConnectionTable.UdpRow6.ProcessId), 24)]
    public void Udp6_offsets_match_sdk(string field, int expected) =>
        Assert.Equal(expected, Marshal.OffsetOf<WindowsConnectionTable.UdpRow6>(field).ToInt32());

    [Theory]
    [InlineData(1, ConnectionState.Closed)]
    [InlineData(2, ConnectionState.Listen)]
    [InlineData(3, ConnectionState.SynSent)]
    [InlineData(4, ConnectionState.SynReceived)]
    [InlineData(5, ConnectionState.Established)]
    [InlineData(11, ConnectionState.TimeWait)]
    [InlineData(12, ConnectionState.Closed)]
    public void Maps_windows_tcp_states(uint state, ConnectionState expected) =>
        Assert.Equal(expected, WindowsConnectionTable.MapState(state));

    [Fact]
    public void Converts_network_order_port()
    {
        Assert.Equal(443, WindowsConnectionTable.ToPort(0x0000BB01));
        Assert.Equal(53, WindowsConnectionTable.ToPort(0x00003500));
    }

    [Fact]
    public void Reads_current_connection_table_without_admin_rights()
    {
        var snapshot = new WindowsConnectionTable().Snapshot();

        Assert.NotNull(snapshot);
        Assert.All(snapshot, connection => Assert.True(connection.ProcessId >= 0));
    }

    [Fact]
    public void Kill_connection_supports_only_ipv4_tcp()
    {
        var tcp = new ConnectionKey(
            L4Protocol.Tcp,
            new IPEndPoint(IPAddress.Parse("192.168.1.10"), 50000),
            new IPEndPoint(IPAddress.Parse("203.0.113.10"), 443));
        var udp = tcp with { Protocol = L4Protocol.Udp };
        var ipv6 = tcp with
        {
            Local = new IPEndPoint(IPAddress.IPv6Loopback, 50000),
            Remote = new IPEndPoint(IPAddress.Parse("2001:db8::1"), 443),
        };

        Assert.True(WindowsConnectionKiller.CanKill(tcp));
        Assert.False(WindowsConnectionKiller.CanKill(udp));
        Assert.False(WindowsConnectionKiller.CanKill(ipv6));
    }
}
