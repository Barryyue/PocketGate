using System.Net;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Domain;
using NetworkControl.Storage.Backup;
using NetworkControl.Storage.Repositories;
using NetworkControl.Storage.Serialization;

namespace NetworkControl.Storage.Tests;

public sealed class RuleImportPlannerTests
{
    private static readonly DateTimeOffset T0 = new(2026, 11, 13, 0, 0, 0, TimeSpan.Zero);

    [Fact]
    public void Merge_adds_new_updates_changed_and_keeps_local_rules()
    {
        var same = NewRule("same.example");
        var changed = NewRule("old.example");
        var localOnly = NewRule("local.example");
        var builtin = NewRule("builtin.example") with { Source = RuleSource.Builtin };
        var incomingNew = NewRule("new.example");

        var plan = RuleImportPlanner.Plan(
            [same, changed, localOnly, builtin],
            [same with { UpdatedAt = T0.AddDays(3) }, changed with { Action = VerdictAction.Deny }, incomingNew, builtin with { Action = VerdictAction.Deny }],
            RuleImportMode.Merge);

        var added = Assert.Single(plan.ToAdd);
        Assert.Equal(incomingNew.Id, added.Id);
        Assert.Equal(RuleSource.Import, added.Source);
        var updated = Assert.Single(plan.ToUpdate);
        Assert.Equal(VerdictAction.Deny, updated.Action);
        Assert.Equal(changed.Source, updated.Source);
        Assert.Empty(plan.ToRemove);
        Assert.Equal(2, plan.Unchanged); // 時間戳不同但內容相同；內置規則不被改寫
    }

    [Fact]
    public void Replace_removes_portable_local_rules_missing_from_the_file()
    {
        var kept = NewRule("kept.example");
        var missing = NewRule("missing.example");
        var temporary = NewRule("temp.example") with { Lifetime = RuleLifetime.UntilRestart };
        var builtin = NewRule("builtin.example") with { Source = RuleSource.Builtin };

        var plan = RuleImportPlanner.Plan([kept, missing, temporary, builtin], [kept], RuleImportMode.Replace);

        Assert.Equal(missing.Id, Assert.Single(plan.ToRemove).Id);
        Assert.Equal(1, plan.Unchanged);
    }

    [Fact]
    public void Address_rules_with_equal_networks_are_unchanged()
    {
        var rule = NewRule("x") with { Conditions = new RuleConditions { Remote = new AddressTarget([IPNetwork.Parse("10.0.0.0/8")]) } };
        var copy = rule with { Conditions = new RuleConditions { Remote = new AddressTarget([IPNetwork.Parse("10.0.0.0/8")]) } };

        var plan = RuleImportPlanner.Plan([rule], [copy], RuleImportMode.Merge);

        Assert.Equal(1, plan.Unchanged);
        Assert.Empty(plan.ToUpdate);
    }

    internal static Rule NewRule(string domain) => new()
    {
        Id = Guid.NewGuid(),
        Conditions = new RuleConditions { Remote = new DomainTarget(domain) },
        Action = VerdictAction.Allow,
        Lifetime = RuleLifetime.Permanent,
        Source = RuleSource.User,
        CreatedAt = T0,
        UpdatedAt = T0,
    };
}

public sealed class ConfigBackupRestoreTests
{
    private static readonly DateTimeOffset Start = new(2026, 11, 1, 8, 0, 0, TimeSpan.Zero);

    private static CancellationToken Ct => TestContext.Current.CancellationToken;

    [Fact]
    public async Task Lists_and_loads_rules_from_backups_without_touching_them()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var repository = new SqliteRuleRepository(fx.Config);
        var rule = RuleImportPlannerTests.NewRule("backup.example");
        await repository.UpsertAsync(rule, Ct);

        var time = new FakeTimeProvider(Start);
        var backup = new ConfigBackup(fx.Config, fx.Options, time, NullLogger<ConfigBackup>.Instance);
        await backup.EnsureTodayAsync(Ct);
        await repository.UpsertAsync(RuleImportPlannerTests.NewRule("after.example"), Ct);
        time.Advance(TimeSpan.FromMinutes(5));
        var snapshot = await backup.CreateRestoreSnapshotAsync(Ct);

        var list = backup.List();
        Assert.Equal(2, list.Count);
        Assert.Contains(list, b => b.Name == "config-20261101.db" && !b.IsRestoreSnapshot);
        Assert.Contains(list, b => b.Name == snapshot && b.IsRestoreSnapshot);
        Assert.Equal("config-restore-20261101-080500.db", snapshot);

        var daily = await backup.LoadRulesAsync("config-20261101.db", Ct);
        Assert.Equal(rule.Id, Assert.Single(daily).Id);
        Assert.Equal(2, (await backup.LoadRulesAsync(snapshot, Ct)).Count);
    }

    [Theory]
    [InlineData("..\\config.db")]
    [InlineData("config.db")]
    [InlineData("config-20261101.db")]
    public async Task Rejects_unknown_or_unmanaged_backup_names(string name)
    {
        await using var fx = await StorageFixture.CreateAsync();
        var backup = new ConfigBackup(fx.Config, fx.Options, new FakeTimeProvider(Start), NullLogger<ConfigBackup>.Instance);

        await Assert.ThrowsAsync<FileNotFoundException>(() => backup.LoadRulesAsync(name, Ct));
    }

    [Fact]
    public async Task Keeps_only_three_restore_snapshots()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var time = new FakeTimeProvider(Start);
        var backup = new ConfigBackup(fx.Config, fx.Options, time, NullLogger<ConfigBackup>.Instance);
        for (var i = 0; i < 5; i++)
        {
            await backup.CreateRestoreSnapshotAsync(Ct);
            time.Advance(TimeSpan.FromMinutes(1));
        }

        Assert.Equal(ConfigBackup.MaxSnapshotCount, backup.List().Count(b => b.IsRestoreSnapshot));
    }
}

public sealed class RuleImportPlannerSourceTests
{
    [Fact]
    public void Restore_keeps_the_original_source_of_new_rules()
    {
        var rule = RuleImportPlannerTests.NewRule("restored.example") with { Source = RuleSource.Prompt };

        var plan = RuleImportPlanner.Plan([], [rule], RuleImportMode.Replace, markNewAsImported: false);

        Assert.Equal(RuleSource.Prompt, Assert.Single(plan.ToAdd).Source);
    }
}
