using NetworkControl.Core.Profiles;

namespace NetworkControl.Engine.Fake;

/// <summary>開發用的可控制網絡狀態；界面 / 探針通過模擬器接口切換。</summary>
public sealed class FakeNetworkMonitor : INetworkMonitor
{
    private NetworkContext _current = new(NetworkKind.Wired, null, null, "Fake Ethernet");

    public NetworkContext Current => Volatile.Read(ref _current);

    public event EventHandler? Changed;

    public void Set(NetworkContext context)
    {
        var previous = Volatile.Read(ref _current);
        if (previous == context)
        {
            return;
        }

        Volatile.Write(ref _current, context);
        Changed?.Invoke(this, EventArgs.Empty);
    }
}
