using NetworkControl.Engine.Windows.Interop;

namespace NetworkControl.Engine.Windows.IntegrationTests;

/// <summary>被阻斷的回環連接要在 5 秒內產生 ClassifyDrop 網絡事件（需要管理員）。</summary>
public class NetEventTests
{
    [Fact]
    public async Task BlockedConnection_RaisesClassifyDropEventWithinFiveSeconds()
    {
        TestEnvironment.RequireAdministrator();

        // 事件收集是全局引擎選項；測試結束後恢復原值，避免留下副作用
        var previous = WfpSession.GetGlobalOption(FwpmEngineOption.CollectNetEvents) ?? 0;
        try
        {
            if (previous != 1)
            {
                WfpSession.SetGlobalOption(FwpmEngineOption.CollectNetEvents, 1);
            }

            using var listener = TestSupport.StartLoopbackListener(out var port);
            using var session = TestSupport.OpenSession();
            using var collector = new DropEventCollector(session.Handle);

            var added = session.Apply([], [TestSupport.BlockLoopbackSpec(port)], TestSupport.FailOnSkipped);
            var filterId = Assert.Single(added).Value;

            Assert.False(
                await TestSupport.TryConnectAsync(port, TimeSpan.FromSeconds(3)),
                "阻斷過濾器存在時連接應該失敗");

            Assert.True(
                await collector.WaitForAsync(filterId, TimeSpan.FromSeconds(5)),
                $"過濾器 {filterId} 應該在 5 秒內產生 ClassifyDrop 事件");
        }
        finally
        {
            if (previous != 1)
            {
                WfpSession.SetGlobalOption(FwpmEngineOption.CollectNetEvents, previous);
            }
        }
    }
}
