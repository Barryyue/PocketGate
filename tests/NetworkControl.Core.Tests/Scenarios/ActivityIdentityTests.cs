using NetworkControl.Core.Activity;
using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Tests.Scenarios;

/// <summary>在解析應用之前就給出判決的模式下，連接日誌仍然要帶上應用信息（應用頁、總覽排行依賴它）。</summary>
public class ActivityIdentityTests
{
    [Theory]
    [InlineData(FilterMode.Disabled, VerdictAction.Allow)]
    [InlineData(FilterMode.BlockAll, VerdictAction.Deny)]
    public async Task Short_circuited_verdicts_still_record_the_app(FilterMode mode, VerdictAction expected)
    {
        await using var host = await ScenarioHost.StartAsync();
        await host.State.SetModeAsync(mode, TestContext.Current.CancellationToken);

        var result = await host.Connect("chrome");

        Assert.Equal(expected, result.Action);
        var recorded = await WaitForEventAsync(host);
        Assert.Equal(VerdictReason.Mode, recorded.Verdict.Reason);
        Assert.NotNull(recorded.AppId);
        Assert.Equal("Google Chrome", recorded.AppName);
    }

    private static async Task<ConnectionEvent> WaitForEventAsync(ScenarioHost host)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        while (true)
        {
            lock (host.Activity)
            {
                if (host.Activity.Count > 0)
                {
                    return host.Activity[^1];
                }
            }

            await Task.Delay(10, timeout.Token);
        }
    }
}
