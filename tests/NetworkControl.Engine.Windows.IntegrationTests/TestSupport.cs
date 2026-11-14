using System.Net;
using System.Net.Sockets;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;
using NetworkControl.Engine.Windows.Compilation;
using NetworkControl.Engine.Windows.Interop;

namespace NetworkControl.Engine.Windows.IntegrationTests;

/// <summary>集成測試的公共輔助：獨立的會話 / 子層、回環地址上的過濾器。</summary>
internal static class TestSupport
{
    /// <summary>回環測試地址；不使用 127.0.0.1，避免和本機正常服務混淆。</summary>
    public static readonly IPAddress LoopbackTarget = IPAddress.Parse("127.0.0.2");

    /// <summary>打開使用獨立提供程序 / 子層（最高權重）的動態會話，不影響正式安裝的服務。</summary>
    public static WfpSession OpenSession() =>
        WfpSession.Open(
            $"NetworkControl IntegrationTests {Guid.NewGuid():N}",
            Guid.NewGuid(),
            Guid.NewGuid(),
            subLayerWeight: 0xFFFF);

    /// <summary>只阻斷目標地址上指定 TCP 端口的出站過濾器。</summary>
    public static FilterSpec BlockLoopbackSpec(int port, string name = "IntegrationTests: block loopback") =>
        new()
        {
            Name = name,
            Family = IpFamily.V4,
            Permit = false,
            Weight = 0x8000_0000_0000_0000,
            Role = FilterRole.Rule,
            RemoteNetworks = [new IPNetwork(LoopbackTarget, 32)],
            RemotePort = PortRange.Single((ushort)port),
            Protocol = L4Protocol.Tcp,
            Inbound = false,
        };

    /// <summary>在 127.0.0.2 上監聽一個隨機端口；不調用 Accept 也能完成 TCP 握手的內核排隊。</summary>
    public static TcpListener StartLoopbackListener(out int port)
    {
        var listener = new TcpListener(LoopbackTarget, 0);
        listener.Start();
        port = ((IPEndPoint)listener.LocalEndpoint).Port;
        return listener;
    }

    /// <summary>嘗試連接；返回 false 表示連接失敗（被 WFP 阻斷或超時）。</summary>
    public static async Task<bool> TryConnectAsync(int port, TimeSpan timeout)
    {
        using var client = new TcpClient();
        using var cts = new CancellationTokenSource(timeout);
        try
        {
            await client.ConnectAsync(LoopbackTarget, port, cts.Token);
            return true;
        }
        catch (SocketException)
        {
            return false;
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    /// <summary>Apply 的 onSkipped 回調：測試中不允許跳過過濾器。</summary>
    public static void FailOnSkipped(FilterSpec spec, Exception exception) =>
        throw new InvalidOperationException($"過濾器 {spec.Name} 未能下發：{exception.Message}", exception);
}
