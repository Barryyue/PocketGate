using NetworkControl.Engine.Windows.Interop;

namespace NetworkControl.Engine.Windows.IntegrationTests;

/// <summary>動態會話、過濾器下發與回環連線行為（需要管理員）。</summary>
public class WfpSessionTests
{
    [Fact]
    public async Task BlockFilter_BlocksLoopbackConnect_DeletingRestores()
    {
        TestEnvironment.RequireAdministrator();

        using var listener = TestSupport.StartLoopbackListener(out var port);
        using var session = TestSupport.OpenSession();
        var spec = TestSupport.BlockLoopbackSpec(port);

        var added = session.Apply([], [spec], TestSupport.FailOnSkipped);
        var filterId = Assert.Single(added).Value;

        Assert.False(
            await TestSupport.TryConnectAsync(port, TimeSpan.FromSeconds(3)),
            "阻斷過濾器存在時，127.0.0.2 上的 TCP 連接應該失敗");

        session.Apply([filterId], [], TestSupport.FailOnSkipped);

        Assert.True(
            await TestSupport.TryConnectAsync(port, TimeSpan.FromSeconds(3)),
            "刪除阻斷過濾器後，TCP 連接應該成功");
    }

    [Fact]
    public void DisposingSession_RemovesFilters()
    {
        TestEnvironment.RequireAdministrator();

        var providerKey = Guid.NewGuid();
        var subLayerKey = Guid.NewGuid();
        var sessionName = $"NetworkControl IntegrationTests {Guid.NewGuid():N}";

        using (var listener = TestSupport.StartLoopbackListener(out var port))
        {
            using (var session = WfpSession.Open(sessionName, providerKey, subLayerKey, subLayerWeight: 0xFFFF))
            {
                var added = session.Apply([], [TestSupport.BlockLoopbackSpec(port)], TestSupport.FailOnSkipped);
                Assert.Single(added);
                Assert.Equal(1, WfpTestNative.CountFilters(providerKey, subLayerKey));
            }

            // 動態會話關閉後 BFE 可能非同步清理；給一個短暫的寬限時間再斷言
            var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(2);
            while (WfpTestNative.CountFilters(providerKey, subLayerKey) != 0 && DateTime.UtcNow < deadline)
            {
                Thread.Sleep(50);
            }

            Assert.Equal(0, WfpTestNative.CountFilters(providerKey, subLayerKey));
        }
    }

    [Fact]
    public void FilterEnumerationTemplate_LayoutMatchesSdk()
    {
        TestEnvironment.RequireAdministrator();
        Assert.SkipUnless(Environment.Is64BitProcess, "佈局按 64 位進程斷言");
        Assert.Equal(72, System.Runtime.InteropServices.Marshal.SizeOf<WfpTestNative.FwpmFilterEnumTemplate0>());
    }
}
