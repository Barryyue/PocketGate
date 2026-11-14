using NetworkControl.Core.Domain;
using NetworkControl.Storage.Repositories;

namespace NetworkControl.Storage.Tests;

public sealed class ProfileRepositoryTests
{
    [Fact]
    public async Task Round_trips_profiles_and_deletes_dedicated_rules_by_cascade()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var ct = TestContext.Current.CancellationToken;
        var profiles = new SqliteProfileRepository(fx.Config);
        var rules = new SqliteRuleRepository(fx.Config);
        var now = DateTimeOffset.UtcNow;
        var profile = new Profile
        {
            Id = Guid.NewGuid(),
            Name = "公司",
            Mode = FilterMode.Whitelist,
            SortOrder = 2,
            AutoActivate = new NetworkControl.Core.Profiles.AutoActivateCondition(
                NetworkControl.Core.Profiles.NetworkKind.Wifi,
                ["CorpNet"],
                []),
        };

        await profiles.UpsertAsync(profile, ct);
        var loaded = Assert.Single(await profiles.LoadAllAsync(ct), p => p.Id == profile.Id);
        Assert.Equal((profile.Name, profile.Mode, profile.SortOrder),
            (loaded.Name, loaded.Mode, loaded.SortOrder));
        Assert.Equal(profile.AutoActivate?.Kind, loaded.AutoActivate?.Kind);
        Assert.Equal(profile.AutoActivate?.Ssids, loaded.AutoActivate?.Ssids);
        Assert.Equal(profile.AutoActivate?.NetworkIds, loaded.AutoActivate?.NetworkIds);
        Assert.Contains(await profiles.LoadAllAsync(ct), p => p.Id == Profile.DefaultId);

        var rule = new Rule
        {
            Id = Guid.NewGuid(),
            ProfileId = profile.Id,
            App = new AppMatcher { PathPattern = "/opt/example" },
            Conditions = RuleConditions.Any,
            Action = VerdictAction.Deny,
            Lifetime = RuleLifetime.Permanent,
            Source = RuleSource.User,
            CreatedAt = now,
            UpdatedAt = now,
        };
        await rules.UpsertAsync(rule, ct);

        await profiles.UpsertAsync(profile with { Name = "公司 2", Mode = FilterMode.Blacklist, AutoActivate = null }, ct);
        var updated = Assert.Single(await profiles.LoadAllAsync(ct), p => p.Id == profile.Id);
        Assert.Equal(("公司 2", FilterMode.Blacklist), (updated.Name, updated.Mode));
        Assert.Null(updated.AutoActivate);

        await profiles.DeleteAsync(profile.Id, ct);
        Assert.DoesNotContain(await profiles.LoadAllAsync(ct), p => p.Id == profile.Id);
        Assert.DoesNotContain(await rules.LoadAllAsync(ct), r => r.Rule.ProfileId == profile.Id);
    }
}
