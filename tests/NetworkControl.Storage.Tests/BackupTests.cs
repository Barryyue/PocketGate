using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Hosting;
using NetworkControl.Storage.Backup;
using NetworkControl.Storage.Repositories;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Tests;

public sealed class ConfigBackupTests
{
    private static readonly DateTimeOffset Start = new(2026, 11, 1, 8, 0, 0, TimeSpan.Zero);

    private static CancellationToken Ct => TestContext.Current.CancellationToken;

    [Fact]
    public async Task Creates_backup_that_contains_rules()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var rule = NewRule();
        await new SqliteRuleRepository(fx.Config).UpsertAsync(rule, Ct);

        var backup = NewBackup(fx, new FakeTimeProvider(Start));
        Assert.True(await backup.EnsureTodayAsync(Ct));

        var path = Path.Combine(fx.Directory, "backups", "config-20261101.db");
        Assert.Equal(1L, await CountRulesAsync(path, rule.Id));
        Assert.Empty(Directory.GetFiles(Path.Combine(fx.Directory, "backups"), "*.tmp"));
    }

    [Fact]
    public async Task Does_not_overwrite_backup_of_the_same_day()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var repository = new SqliteRuleRepository(fx.Config);
        var first = NewRule();
        await repository.UpsertAsync(first, Ct);

        var time = new FakeTimeProvider(Start);
        var backup = NewBackup(fx, time);
        Assert.True(await backup.EnsureTodayAsync(Ct));

        // 模擬升級：同一天再次啟動並修改了數據庫，當天的（遷移前）副本必須保持不變
        var second = NewRule();
        await repository.UpsertAsync(second, Ct);
        time.Advance(TimeSpan.FromHours(10));
        Assert.False(await backup.EnsureTodayAsync(Ct));

        var path = Path.Combine(fx.Directory, "backups", "config-20261101.db");
        Assert.Equal(1L, await CountRulesAsync(path, first.Id));
        Assert.Equal(0L, await CountRulesAsync(path, second.Id));
    }

    [Fact]
    public async Task Keeps_only_the_seven_most_recent_backups_and_ignores_other_files()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var directory = Path.Combine(fx.Directory, "backups");
        Directory.CreateDirectory(directory);
        var manual = Path.Combine(directory, "config-manual.db");
        await File.WriteAllTextAsync(manual, "user file", Ct);

        var time = new FakeTimeProvider(Start);
        var backup = NewBackup(fx, time);
        for (var day = 0; day < 9; day++)
        {
            Assert.True(await backup.EnsureTodayAsync(Ct));
            time.Advance(TimeSpan.FromDays(1));
        }

        var backups = Directory.GetFiles(directory, "config-2*.db")
            .Select(Path.GetFileName)
            .Order(StringComparer.Ordinal)
            .ToArray();
        Assert.Equal(ConfigBackup.MaxBackupCount, backups.Length);
        Assert.Equal("config-20261103.db", backups[0]);
        Assert.Equal("config-20261109.db", backups[^1]);
        Assert.True(File.Exists(manual));
    }

    [Fact]
    public async Task Skips_when_database_does_not_exist_yet()
    {
        var directory = Path.Combine(Path.GetTempPath(), "netcontrol-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var options = Options.Create(new StorageOptions { DataDirectory = directory });
            var backup = new ConfigBackup(new ConfigDatabase(options), options, new FakeTimeProvider(Start), NullLogger<ConfigBackup>.Instance);

            Assert.False(await backup.EnsureTodayAsync(Ct));
            Assert.False(Directory.Exists(Path.Combine(directory, "backups")));
            Assert.False(File.Exists(Path.Combine(directory, "config.db")));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void Startup_backup_runs_before_migrations()
    {
        Assert.True(new ConfigBackupStartupTask(null!).Order < StartupOrder.Storage);
    }

    [Fact]
    public async Task Background_service_creates_a_backup_after_the_date_changes()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var time = new FakeTimeProvider(Start);
        using var service = new ConfigBackupService(NewBackup(fx, time), time);
        var directory = Path.Combine(fx.Directory, "backups");

        await service.StartAsync(Ct);
        try
        {
            await WaitForFileAsync(Path.Combine(directory, "config-20261101.db"));

            // 跨過午夜：到下一次整點檢查時建立新一天的備份
            time.Advance(TimeSpan.FromHours(16));
            await WaitForFileAsync(Path.Combine(directory, "config-20261102.db"));
        }
        finally
        {
            await service.StopAsync(Ct);
        }
    }

    private static ConfigBackup NewBackup(StorageFixture fx, TimeProvider time) =>
        new(fx.Config, fx.Options, time, NullLogger<ConfigBackup>.Instance);

    private static Rule NewRule() => new()
    {
        Id = Guid.NewGuid(),
        Conditions = new RuleConditions { Remote = new DomainTarget("example.com") },
        Action = VerdictAction.Deny,
        Lifetime = RuleLifetime.Permanent,
        Source = RuleSource.User,
        CreatedAt = Start,
        UpdatedAt = Start,
    };

    private static async Task<long> CountRulesAsync(string path, Guid id)
    {
        await using var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadOnly,
            Pooling = false,
        }.ToString());
        await connection.OpenAsync(Ct);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM rules WHERE id = $id";
        command.Parameters.AddWithValue("$id", id.ToString());
        return Convert.ToInt64(await command.ExecuteScalarAsync(Ct), System.Globalization.CultureInfo.InvariantCulture);
    }

    private static async Task WaitForFileAsync(string path)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(Ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(10));
        while (!File.Exists(path))
        {
            await Task.Delay(20, timeout.Token);
        }
    }
}
