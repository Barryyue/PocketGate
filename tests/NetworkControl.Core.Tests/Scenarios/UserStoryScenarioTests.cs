using Microsoft.Extensions.DependencyInjection;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Tests.Scenarios;

/// <summary>M0 驗收場景：在模擬引擎上跑通需求文檔中的 US-01 / 02 / 03 / 07，以及超時、模式等兜底行為。</summary>
public class UserStoryScenarioTests
{
    [Fact]
    public async Task US01_new_app_prompts_and_permanent_allow_creates_rule()
    {
        await using var host = await ScenarioHost.StartAsync();

        var first = host.Connect("chrome");
        var prompt = await host.NextPromptAsync();
        Assert.Equal("Google Chrome", prompt.App?.DisplayName);
        Assert.Contains("api.github.com", prompt.Flows[0].Domains);
        Assert.True(prompt.IsConnectionHeld);

        var answer = await host.AnswerAsync(prompt, VerdictAction.Allow, RuleLifetime.Permanent);
        Assert.Equal(VerdictAction.Allow, (await first).Action);
        Assert.NotNull(answer.CreatedRuleId);
        Assert.True(host.RuleRepository.Rules.ContainsKey(answer.CreatedRuleId!.Value));

        // 第二次連接直接命中規則，不再彈窗
        var second = await host.Connect("chrome", "www.google.com");
        Assert.Equal(VerdictAction.Allow, second.Action);
        Assert.Equal(VerdictReason.Rule, host.Activity[^1].Verdict.Reason);
    }

    [Fact]
    public async Task US02_allow_once_does_not_persist()
    {
        await using var host = await ScenarioHost.StartAsync();

        var first = host.Connect("tool");
        await host.AnswerAsync(await host.NextPromptAsync(), VerdictAction.Allow, RuleLifetime.Once);
        Assert.Equal(VerdictAction.Allow, (await first).Action);
        Assert.Empty(host.Rules.All);

        // 下一次連接再次詢問
        var second = host.Connect("tool");
        var prompt = await host.NextPromptAsync();
        await host.AnswerAsync(prompt, VerdictAction.Deny, RuleLifetime.Once);
        Assert.Equal(VerdictAction.Deny, (await second).Action);
    }

    [Fact]
    public async Task US02_allow_for_15_minutes_expires()
    {
        await using var host = await ScenarioHost.StartAsync();

        var first = host.Connect("game");
        await host.AnswerAsync(await host.NextPromptAsync(), VerdictAction.Allow, RuleLifetime.Timed, TimeSpan.FromMinutes(15));
        await first;

        await host.AdvanceAsync(TimeSpan.FromMinutes(10));
        Assert.Equal(VerdictAction.Allow, (await host.Connect("game")).Action);

        await host.AdvanceAsync(TimeSpan.FromMinutes(6));
        Assert.Empty(host.Rules.All);
        _ = host.Connect("game");
        Assert.NotNull(await host.NextPromptAsync());
    }

    [Fact]
    public async Task US03_allow_until_app_quits()
    {
        await using var host = await ScenarioHost.StartAsync();

        var first = host.Connect("vscode");
        await host.AnswerAsync(await host.NextPromptAsync(), VerdictAction.Allow, RuleLifetime.UntilProcessExit);
        await first;
        Assert.Single(host.Rules.All);

        Assert.True(host.Simulator.ExitApp("vscode"));
        SpinWait.SpinUntil(() => host.Rules.All.Count == 0, TimeSpan.FromSeconds(2));
        Assert.Empty(host.Rules.All);

        // 重新啟動後再次詢問
        _ = host.Connect("vscode");
        Assert.NotNull(await host.NextPromptAsync());
    }

    [Fact]
    public async Task US07_block_all_and_restore_previous_mode()
    {
        await using var host = await ScenarioHost.StartAsync();
        await host.State.SetModeAsync(FilterMode.Blacklist, TestContext.Current.CancellationToken);

        await host.State.SetModeAsync(FilterMode.BlockAll, TestContext.Current.CancellationToken);
        Assert.Equal(VerdictAction.Deny, (await host.Connect("chrome")).Action);

        await host.State.RestoreFromBlockAllAsync(TestContext.Current.CancellationToken);
        Assert.Equal(FilterMode.Blacklist, host.State.Mode);
        Assert.Equal(VerdictAction.Allow, (await host.Connect("chrome")).Action);
    }

    [Fact]
    public async Task Prompt_timeout_applies_default_action()
    {
        await using var host = await ScenarioHost.StartAsync();

        var flow = host.Connect("python");
        await host.NextPromptAsync();
        await host.AdvanceAsync(UserSettings.Default.PromptTimeout + TimeSpan.FromSeconds(1));

        Assert.Equal(VerdictAction.Deny, (await flow).Action);
        Assert.Equal(VerdictReason.PromptTimeout, host.Activity[^1].Verdict.Reason);
    }

    [Fact]
    public async Task Concurrent_flows_from_same_app_merge_into_one_prompt()
    {
        await using var host = await ScenarioHost.StartAsync();

        var a = host.Connect("discord", "discord.com");
        var added = await host.NextPromptAsync();
        var b = host.Connect("discord", "cdn.discordapp.com");
        var updated = await host.NextPromptAsync(PromptEventKind.Updated);

        Assert.Equal(added.Id, updated.Id);
        Assert.Equal(2, updated.Flows.Count);

        // 「此應用的任意連接」覆蓋兩條待決連接
        var result = await host.AnswerAsync(updated, VerdictAction.Deny, RuleLifetime.Permanent);
        Assert.Equal(2, result.ResolvedFlows);
        Assert.Equal(VerdictAction.Deny, (await a).Action);
        Assert.Equal(VerdictAction.Deny, (await b).Action);
    }

    [Fact]
    public async Task Domain_scoped_answer_leaves_other_flows_pending()
    {
        await using var host = await ScenarioHost.StartAsync();

        var github = host.Connect("vscode", "api.github.com");
        await host.NextPromptAsync();
        var telemetry = host.Connect("vscode", "telemetry.example.com");
        var prompt = await host.NextPromptAsync(PromptEventKind.Updated);

        var result = await host.AnswerAsync(prompt, VerdictAction.Allow, RuleLifetime.Permanent, scope: PromptScope.Domain);
        Assert.Equal(1, result.ResolvedFlows);
        Assert.Equal(1, result.RemainingFlows);
        Assert.Equal(VerdictAction.Allow, (await github).Action);
        Assert.False(telemetry.IsCompleted);
    }

    [Fact]
    public async Task Whitelist_mode_denies_unknown_apps_without_prompt()
    {
        await using var host = await ScenarioHost.StartAsync();
        await host.State.SetModeAsync(FilterMode.Whitelist, TestContext.Current.CancellationToken);

        var result = await host.Connect("tool");

        Assert.Equal(VerdictAction.Deny, result.Action);
        Assert.Equal(0, host.Prompts.PendingFlowCount);
    }

    [Fact]
    public async Task No_ui_session_uses_default_action()
    {
        await using var host = await ScenarioHost.StartAsync(withUiSession: false);

        var result = await host.Connect("chrome");

        Assert.Equal(VerdictAction.Deny, result.Action);
        Assert.Equal(VerdictReason.NoUserSession, host.Activity[^1].Verdict.Reason);
    }

    [Fact]
    public async Task Tampered_app_triggers_tamper_prompt()
    {
        await using var host = await ScenarioHost.StartAsync();

        var first = host.Connect("tool");
        await host.AnswerAsync(await host.NextPromptAsync(), VerdictAction.Allow, RuleLifetime.Permanent);
        await first;

        // 程序被替換：哈希變化，新進程啟動
        host.Simulator.ExitApp("tool");
        host.Services.GetRequiredService<NetworkControl.Engine.Fake.FakeProcessTable>()
            .Tamper("tool", newSignerId: null, newSha256: "0000000000000000000000000000000000000000000000000000000000000000");

        _ = host.Connect("tool");
        var prompt = await host.NextPromptAsync();
        Assert.True(prompt.IsTamperAlert);
    }
}
