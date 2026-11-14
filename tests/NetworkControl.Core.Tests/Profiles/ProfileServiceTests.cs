using NetworkControl.Core.Domain;
using NetworkControl.Core.Profiles;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Tests.Scenarios;

namespace NetworkControl.Core.Tests.Profiles;

public sealed class ProfileServiceTests
{
    [Fact]
    public async Task Default_profile_exists_and_profile_can_be_created_activated_and_deleted()
    {
        await using var host = await ScenarioHost.StartAsync(withUiSession: false);
        var ct = TestContext.Current.CancellationToken;

        Assert.Contains(host.Profiles.All, p => p.Id == Profile.DefaultId);

        var company = await host.Profiles.CreateAsync("公司", FilterMode.Whitelist, null, ct);
        Assert.Contains(host.Profiles.All, p => p.Id == company.Id && p.Name == "公司");

        await Assert.ThrowsAsync<ArgumentException>(() =>
            host.Profiles.CreateAsync("公司", FilterMode.Ask, null, ct));
        await Assert.ThrowsAsync<ArgumentException>(() =>
            host.Profiles.CreateAsync("公司", FilterMode.BlockAll, null, ct));
        await Assert.ThrowsAsync<ArgumentException>(() =>
            host.Profiles.CreateAsync(new string('x', 41), FilterMode.Ask, null, ct));

        await host.Profiles.ActivateAsync(company.Id, ProfileSwitchReason.User, ct);
        Assert.Equal(company.Id, host.State.ActiveProfileId);
        Assert.Equal(FilterMode.Whitelist, host.State.Mode);
        Assert.Equal(company.Id, host.Profiles.Active.Id);

        await Assert.ThrowsAsync<ArgumentException>(() => host.Profiles.DeleteAsync(company.Id, ct));
        await host.Profiles.ActivateAsync(Profile.DefaultId, ProfileSwitchReason.User, ct);
        await host.Profiles.DeleteAsync(company.Id, ct);
        Assert.DoesNotContain(host.Profiles.All, p => p.Id == company.Id);
    }

    [Fact]
    public async Task Activating_a_profile_while_block_all_keeps_blocking_and_restores_to_profile_mode()
    {
        await using var host = await ScenarioHost.StartAsync(withUiSession: false);
        var ct = TestContext.Current.CancellationToken;
        var company = await host.Profiles.CreateAsync("公司", FilterMode.Whitelist, null, ct);

        await host.State.SetModeAsync(FilterMode.BlockAll, ct);
        await host.Profiles.ActivateAsync(company.Id, ProfileSwitchReason.User, ct);

        Assert.Equal(FilterMode.BlockAll, host.State.Mode);
        Assert.Equal(company.Id, host.State.ActiveProfileId);

        await host.State.RestoreFromBlockAllAsync(ct);
        Assert.Equal(FilterMode.Whitelist, host.State.Mode);
    }

    [Fact]
    public async Task Creating_a_profile_copies_only_its_dedicated_persistent_rules()
    {
        await using var host = await ScenarioHost.StartAsync(withUiSession: false);
        var ct = TestContext.Current.CancellationToken;
        var source = await host.Profiles.CreateAsync("來源", FilterMode.Ask, null, ct);
        var now = host.Time.GetUtcNow();
        var permanent = NewRule(source.Id, RuleLifetime.Permanent, now);
        var once = NewRule(source.Id, RuleLifetime.Once, now);
        await host.Rules.AddAsync(permanent, ct);
        await host.Rules.AddAsync(once, ct);

        var target = await host.Profiles.CreateAsync("目標", FilterMode.Ask, source.Id, ct);
        var copied = host.Rules.All.Single(r => r.ProfileId == target.Id);

        Assert.NotEqual(permanent.Id, copied.Id);
        Assert.Equal(permanent.Action, copied.Action);
        Assert.Equal(permanent.App, copied.App);
        Assert.DoesNotContain(host.Rules.All, r => r.ProfileId == target.Id && r.Lifetime == RuleLifetime.Once);
    }

    [Fact]
    public async Task Deleting_a_profile_removes_its_rules_from_the_rule_service()
    {
        await using var host = await ScenarioHost.StartAsync(withUiSession: false);
        var ct = TestContext.Current.CancellationToken;
        var profile = await host.Profiles.CreateAsync("臨時", FilterMode.Ask, null, ct);
        var rule = NewRule(profile.Id, RuleLifetime.Permanent, host.Time.GetUtcNow());
        await host.Rules.AddAsync(rule, ct);

        await host.Profiles.DeleteAsync(profile.Id, ct);

        Assert.DoesNotContain(host.Rules.All, r => r.ProfileId == profile.Id);
    }

    [Fact]
    public async Task Revision_changes_on_every_profile_mutation()
    {
        await using var host = await ScenarioHost.StartAsync(withUiSession: false);
        var ct = TestContext.Current.CancellationToken;
        var initial = host.Profiles.Revision;

        var profile = await host.Profiles.CreateAsync("版本", FilterMode.Ask, null, ct);
        var afterCreate = host.Profiles.Revision;
        Assert.True(afterCreate > initial);

        await host.Profiles.UpdateAsync(profile with { Name = "版本 2" }, ct);
        Assert.True(host.Profiles.Revision > afterCreate);

        await host.Profiles.ActivateAsync(profile.Id, ProfileSwitchReason.User, ct);
        await host.Profiles.ActivateAsync(Profile.DefaultId, ProfileSwitchReason.User, ct);
        var afterActivate = host.Profiles.Revision;

        await host.Profiles.DeleteAsync(profile.Id, ct);
        Assert.True(host.Profiles.Revision > afterActivate);
    }

    [Fact]
    public async Task Activate_and_delete_cannot_both_succeed()
    {
        await using var host = await ScenarioHost.StartAsync(withUiSession: false);
        var ct = TestContext.Current.CancellationToken;
        var profile = await host.Profiles.CreateAsync("競態", FilterMode.Ask, null, ct);

        var activate = host.Profiles.ActivateAsync(profile.Id, ProfileSwitchReason.User, ct);
        var delete = host.Profiles.DeleteAsync(profile.Id, ct);
        await Task.WhenAll(Record(activate), Record(delete));

        // 不能留下「ActiveProfileId 指向已刪除配置文件」的狀態
        Assert.False(host.Profiles.Find(profile.Id) is null && host.State.ActiveProfileId == profile.Id);
    }

    private static async Task Record(Task task)
    {
        try
        {
            await task;
        }
        catch (Exception ex) when (ex is ArgumentException or KeyNotFoundException)
        {
            // 其中一方失敗是預期結果
        }
    }

    private static Rule NewRule(Guid profileId, RuleLifetime lifetime, DateTimeOffset now) => new()
    {
        Id = Guid.NewGuid(),
        ProfileId = profileId,
        App = new AppMatcher { PathPattern = "/opt/example" },
        Conditions = RuleConditions.Any,
        Action = VerdictAction.Deny,
        Lifetime = lifetime,
        Source = RuleSource.User,
        CreatedAt = now,
        UpdatedAt = now,
    };
}
