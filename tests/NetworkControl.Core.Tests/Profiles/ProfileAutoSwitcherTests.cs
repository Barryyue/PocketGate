using Microsoft.Extensions.DependencyInjection;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Profiles;
using NetworkControl.Core.Tests.Scenarios;
using NetworkControl.Engine.Fake;

namespace NetworkControl.Core.Tests.Profiles;

public sealed class AutoActivateConditionTests
{
    [Fact]
    public void Matches_kind_and_ssid_case_insensitively()
    {
        var condition = new AutoActivateCondition(NetworkKind.Wifi, ["CorpNet"], []);
        Assert.True(condition.Matches(new NetworkContext(NetworkKind.Wifi, "corpnet", null, "CorpNet")));
        Assert.False(condition.Matches(new NetworkContext(NetworkKind.Cellular, "CorpNet", null, "CorpNet")));
        Assert.False(condition.Matches(new NetworkContext(NetworkKind.Wifi, "Home", null, "Home")));
    }

    [Fact]
    public void Empty_condition_never_matches()
    {
        Assert.False(AutoActivateCondition.Empty.Matches(new NetworkContext(NetworkKind.Wifi, "CorpNet", null, "CorpNet")));
    }

    [Fact]
    public void Network_id_must_match_exactly()
    {
        var condition = new AutoActivateCondition(null, [], ["network-1"]);
        Assert.True(condition.Matches(new NetworkContext(NetworkKind.Wired, null, "network-1", "Office")));
        Assert.False(condition.Matches(new NetworkContext(NetworkKind.Wired, null, "network-2", "Office")));
    }
}

public sealed class ProfileAutoSwitcherTests
{
    private static async Task SwitchUntilAsync(ScenarioHost host, Func<bool> predicate, string message)
    {
        // 後台的 PeriodicTimer 使用虛擬時間：並行跑整個解決方案時，單次推進後工作可能還沒輪到執行，
        // 因此每次等待都再推進一小段虛擬時間，避免對排程速度的依賴。
        for (var attempt = 0; attempt < 100; attempt++)
        {
            if (predicate())
            {
                return;
            }

            await host.AdvanceAsync(TimeSpan.FromSeconds(1));
            await Task.Delay(20, TestContext.Current.CancellationToken);
        }

        Assert.True(predicate(), message);
    }

    [Fact]
    public async Task Switches_to_matching_profile_after_debounce_and_respects_manual_override()
    {
        await using var host = await ScenarioHost.StartAsync(withUiSession: false);
        var ct = TestContext.Current.CancellationToken;
        var monitor = host.Services.GetRequiredService<FakeNetworkMonitor>();
        var company = await host.Profiles.CreateAsync("公司", FilterMode.Whitelist, null, ct);
        await host.Profiles.UpdateAsync(company with
        {
            AutoActivate = new AutoActivateCondition(NetworkKind.Wifi, ["CorpNet"], []),
        }, ct);

        monitor.Set(new NetworkContext(NetworkKind.Wifi, "corpnet", null, "CorpNet"));
        await SwitchUntilAsync(host, () => host.State.ActiveProfileId == company.Id, "自動切換到公司");

        await host.Profiles.ActivateAsync(Profile.DefaultId, ProfileSwitchReason.User, ct);
        await host.AdvanceAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(Profile.DefaultId, host.State.ActiveProfileId);

        // 換到另一個網絡後，手動覆蓋解除
        monitor.Set(new NetworkContext(NetworkKind.Wifi, "home", null, "Home"));
        monitor.Set(new NetworkContext(NetworkKind.Wifi, "corpnet", null, "CorpNet"));
        await SwitchUntilAsync(host, () => host.State.ActiveProfileId == company.Id, "換網絡後重新自動切換");
    }
}
