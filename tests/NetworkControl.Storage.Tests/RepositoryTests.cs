using System.Globalization;
using System.Net;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Storage.Activity;
using NetworkControl.Storage.Repositories;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Tests;

public class MigratorTests
{
    [Fact]
    public async Task Migrations_are_discovered_in_order_and_idempotent()
    {
        await using var fx = await StorageFixture.CreateAsync();

        var versions = Migrator.Discover("NetworkControl.Storage.Migrations.Config.").Select(m => m.Version).ToArray();
        Assert.NotEmpty(versions);
        Assert.Equal(versions.Order(), versions);

        // 再次遷移不應重複執行任何腳本
        Assert.Equal(0, await Migrator.MigrateAsync(fx.Config, NullLogger.Instance, TestContext.Current.CancellationToken));
    }
}

public class RuleRepositoryTests
{
    private static readonly DateTimeOffset T0 = new(2026, 11, 12, 8, 0, 0, TimeSpan.Zero);

    [Fact]
    public async Task Round_trips_all_rule_fields()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var repo = new SqliteRuleRepository(fx.Config);
        var rule = new Rule
        {
            Id = Guid.NewGuid(),
            ProfileId = Profile.DefaultId,
            App = new AppMatcher { PathPattern = "/opt/discord/app-*/Discord", RequiredSignerId = "Discord Inc." },
            Conditions = new RuleConditions
            {
                Direction = Direction.Outbound,
                Protocol = L4Protocol.Udp,
                Remote = new AddressTarget([IPNetwork.Parse("10.0.0.0/8"), IPNetwork.Parse("fd00::/8")]),
                RemotePort = new PortRange(50000, 50100),
            },
            Action = VerdictAction.Deny,
            Lifetime = RuleLifetime.Timed,
            ExpiresAt = T0.AddHours(1),
            Source = RuleSource.Prompt,
            Enabled = false,
            ManualPriority = 3,
            Note = "備註",
            CreatedAt = T0,
            UpdatedAt = T0.AddMinutes(1),
        };

        await repo.UpsertAsync(rule, TestContext.Current.CancellationToken);
        var loaded = Assert.Single(await repo.LoadAllAsync(TestContext.Current.CancellationToken)).Rule;

        Assert.Equal(rule.Id, loaded.Id);
        Assert.Equal(rule.ProfileId, loaded.ProfileId);
        Assert.Equal(rule.App, loaded.App);
        Assert.Equal(rule.Conditions.Direction, loaded.Conditions.Direction);
        Assert.Equal(rule.Conditions.Protocol, loaded.Conditions.Protocol);
        Assert.Equal(rule.Conditions.RemotePort, loaded.Conditions.RemotePort);
        Assert.Equal(
            ((AddressTarget)rule.Conditions.Remote!).Networks,
            ((AddressTarget)loaded.Conditions.Remote!).Networks);
        Assert.Equal(rule.ExpiresAt, loaded.ExpiresAt);
        Assert.Equal((rule.Action, rule.Lifetime, rule.Source, rule.Enabled, rule.ManualPriority, rule.Note),
            (loaded.Action, loaded.Lifetime, loaded.Source, loaded.Enabled, loaded.ManualPriority, loaded.Note));
    }

    [Fact]
    public async Task Delete_moves_rule_to_history_and_hits_accumulate()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var repo = new SqliteRuleRepository(fx.Config);
        var keep = NewRule(new DomainTarget("*.github.com"));
        var drop = NewRule(new ZoneTarget(NetworkZone.Internet));
        await repo.UpsertAsync(keep, TestContext.Current.CancellationToken);
        await repo.UpsertAsync(drop, TestContext.Current.CancellationToken);

        await repo.RecordHitsAsync([new RuleHitDelta(keep.Id, 3, T0.AddMinutes(1))], TestContext.Current.CancellationToken);
        await repo.RecordHitsAsync([new RuleHitDelta(keep.Id, 2, T0.AddMinutes(2))], TestContext.Current.CancellationToken);
        await repo.DeleteAsync([drop], RuleEndReason.Deleted, T0.AddMinutes(3), TestContext.Current.CancellationToken);

        var stored = Assert.Single(await repo.LoadAllAsync(TestContext.Current.CancellationToken));
        Assert.Equal(keep.Id, stored.Rule.Id);
        Assert.Equal(5, stored.HitCount);
        Assert.Equal(T0.AddMinutes(2), stored.LastHitAt);

        await using var connection = await fx.Config.OpenAsync(TestContext.Current.CancellationToken);
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM rule_history WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", drop.Id.ToString());
        Assert.Equal(1L, Convert.ToInt64(await cmd.ExecuteScalarAsync(TestContext.Current.CancellationToken), CultureInfo.InvariantCulture));
    }

    private static Rule NewRule(RemoteTarget target) => new()
    {
        Id = Guid.NewGuid(),
        Conditions = new RuleConditions { Remote = target },
        Action = VerdictAction.Allow,
        Lifetime = RuleLifetime.Permanent,
        Source = RuleSource.User,
        CreatedAt = T0,
        UpdatedAt = T0,
    };
}

public class AppAndSettingsRepositoryTests
{
    [Fact]
    public async Task Same_app_key_keeps_its_id_and_updates_metadata()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var repo = new SqliteAppRepository(fx.Config, TimeProvider.System);
        var key = new AppKey("/usr/bin/curl");

        var first = await repo.UpsertAsync(key, new AppInspection(new FileStamp(1, 1), "curl", null, "aaa"), TestContext.Current.CancellationToken);
        var second = await repo.UpsertAsync(key, new AppInspection(new FileStamp(2, 2), "curl 8", new CodeSignature("curl-pkg"), null), TestContext.Current.CancellationToken);
        var other = await repo.UpsertAsync(new AppKey("/usr/bin/curl", ServiceName: "svc"), new AppInspection(new FileStamp(1, 1), null, null, null), TestContext.Current.CancellationToken);

        Assert.Equal(first.AppId, second.AppId);
        Assert.NotEqual(first.AppId, other.AppId);
        Assert.Equal("curl 8", second.DisplayName);
        Assert.Equal("curl-pkg", second.Signature?.SignerId);
        Assert.Equal(second, await repo.GetAsync(second.AppId, TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task Settings_and_runtime_state_round_trip()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var repo = new SqliteSettingsRepository(fx.Config);
        Assert.Null(await repo.LoadUserSettingsAsync(TestContext.Current.CancellationToken));

        var settings = UserSettings.Default with { PromptTimeout = TimeSpan.FromSeconds(45), FailPolicy = FailPolicy.FailClosed };
        var state = new RuntimeStateRecord { Mode = FilterMode.BlockAll, ModeBeforeBlockAll = FilterMode.Whitelist };
        await repo.SaveUserSettingsAsync(settings, TestContext.Current.CancellationToken);
        await repo.SaveRuntimeStateAsync(state, TestContext.Current.CancellationToken);

        Assert.Equal(settings, await repo.LoadUserSettingsAsync(TestContext.Current.CancellationToken));
        Assert.Equal(state, await repo.LoadRuntimeStateAsync(TestContext.Current.CancellationToken));
    }
}

public class ActivityLogTests
{
    [Fact]
    public async Task Identical_connections_in_same_minute_are_aggregated()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var time = new FakeTimeProvider(new DateTimeOffset(2026, 11, 12, 8, 0, 10, TimeSpan.Zero));
        var settings = new SettingsService(new SqliteSettingsRepository(fx.Config));
        var writer = new ActivityLogWriter(fx.Activity, settings, fx.Options, time, NullLogger<ActivityLogWriter>.Instance);
        var query = new SqliteActivityQuery(fx.Activity);

        await writer.StartAsync(TestContext.Current.CancellationToken);
        writer.Consume(Event(time.GetUtcNow(), VerdictAction.Allow));
        writer.Consume(Event(time.GetUtcNow().AddSeconds(20), VerdictAction.Allow));
        writer.Consume(Event(time.GetUtcNow().AddSeconds(30), VerdictAction.Deny));
        writer.Consume(Event(time.GetUtcNow().AddMinutes(2), VerdictAction.Allow));
        await writer.StopAsync(TestContext.Current.CancellationToken);

        var entries = await query.QueryAsync(new LogQuery(), TestContext.Current.CancellationToken);
        Assert.Equal(3, entries.Count);
        Assert.Equal(2, entries.Single(e => e.Verdict.Action == VerdictAction.Allow && e.Count > 1).Count);

        var denied = await query.QueryAsync(new LogQuery { Action = VerdictAction.Deny }, TestContext.Current.CancellationToken);
        Assert.Single(denied);

        var byKeyword = await query.QueryAsync(new LogQuery { Keyword = "github" }, TestContext.Current.CancellationToken);
        Assert.Equal(3, byKeyword.Count);
        Assert.Empty(await query.QueryAsync(new LogQuery { Keyword = "100%_" }, TestContext.Current.CancellationToken));
    }

    private static ConnectionEvent Event(DateTimeOffset at, VerdictAction action) => new(
        at,
        AppId: 7,
        AppPath: "/usr/bin/curl",
        AppName: "curl",
        Direction.Outbound,
        L4Protocol.Tcp,
        new IPEndPoint(IPAddress.Parse("192.168.1.10"), 50000),
        new IPEndPoint(IPAddress.Parse("140.82.112.3"), 443),
        "api.github.com",
        new Verdict(action, VerdictReason.Rule));
}
