using System.ComponentModel;
using NetworkControl.App.Services;
using NetworkControl.App.ViewModels;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Tests;

public sealed class ConnectionRowSyncTests
{
    private static readonly AppIconCache Icons = new();

    [Fact]
    public void Update_synchronizes_connection_text_and_kill_state()
    {
        // 這幾個屬性不讀取本地化文本；測試不需要建立 AtomUI 的語言服務
        var row = new ConnectionItemViewModel(Connection(Proto.ConnectionStateKind.Listen), null!, false, Icons);
        var changed = new List<string>();
        row.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName is not null)
            {
                changed.Add(e.PropertyName);
            }
        };

        var newer = Connection(Proto.ConnectionStateKind.Established, @"C:\Apps\new.exe");
        row.Update(newer, true);

        Assert.Same(newer, row.Connection);
        Assert.True(row.CanKill);
        Assert.Contains(nameof(ConnectionItemViewModel.AppName), changed);
        Assert.Contains(nameof(ConnectionItemViewModel.StateText), changed);
        Assert.Contains(nameof(ConnectionItemViewModel.CanKill), changed);
    }

    [Fact]
    public void Merger_reuses_a_row_and_updates_it_instead_of_creating_a_new_one()
    {
        var existing = new ConnectionItemViewModel(Connection(Proto.ConnectionStateKind.Listen), null!, false, Icons);
        var incoming = Connection(Proto.ConnectionStateKind.Established, @"C:\Apps\new.exe");
        var created = 0;

        var merged = ConnectionRowMerger.Merge(
            [incoming],
            [existing],
            static connection => ConnectionItemViewModel.KeyOf(connection),
            static row => row.Key,
            connection =>
            {
                created++;
                return new ConnectionItemViewModel(connection, null!, false, Icons);
            },
            (row, connection) => row.Update(connection, true));

        Assert.Equal(0, created);
        Assert.Same(existing, Assert.Single(merged));
        Assert.Same(incoming, existing.Connection);
        Assert.True(existing.CanKill);
    }

    [Fact]
    public void Merger_creates_a_row_only_for_a_new_connection()
    {
        var existing = new ConnectionItemViewModel(Connection(Proto.ConnectionStateKind.Listen), null!, false, Icons);
        var incoming = Connection(Proto.ConnectionStateKind.Established, @"C:\Apps\other.exe", pid: 99);
        var created = 0;

        var merged = ConnectionRowMerger.Merge(
            [incoming],
            [existing],
            static connection => ConnectionItemViewModel.KeyOf(connection),
            static row => row.Key,
            connection =>
            {
                created++;
                return new ConnectionItemViewModel(connection, null!, false, Icons);
            },
            (row, connection) => row.Update(connection, false));

        Assert.Equal(1, created);
        Assert.NotSame(existing, Assert.Single(merged));
    }

    private static Proto.LiveConnection Connection(Proto.ConnectionStateKind state, string appPath = @"C:\Apps\demo.exe", int pid = 42) => new()
    {
        Protocol = Proto.Protocol.Tcp,
        State = state,
        Pid = pid,
        AppName = "Demo",
        AppPath = appPath,
        Local = new Proto.IpEndpoint { Address = "192.168.1.10", Port = 50_000 },
        Remote = new Proto.IpEndpoint { Address = "140.82.112.3", Port = 443 },
    };
}
