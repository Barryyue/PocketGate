using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Diagnostics;
using NetworkControl.Storage.Activity;
using NetworkControl.Storage.Diagnostics;

namespace NetworkControl.Storage.Tests;

public class StorageDiagnosticsContributorTests
{
    [Fact]
    public async Task Privacy_sensitive_parts_are_only_collected_when_requested()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var sink = new MemorySink();

        await Create(fx).CollectAsync(sink, new DiagnosticsCollectOptions(), TestContext.Current.CancellationToken);

        Assert.Equal(["databases.txt"], sink.Entries.Keys);
        var summary = System.Text.Encoding.UTF8.GetString(sink.Entries["databases.txt"]);
        Assert.Contains("config.db", summary, StringComparison.Ordinal);
        Assert.Contains("Integrity ok", summary, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Configuration_backup_is_a_consistent_sqlite_database()
    {
        await using var fx = await StorageFixture.CreateAsync();
        var sink = new MemorySink();

        await Create(fx).CollectAsync(sink, new DiagnosticsCollectOptions { IncludeConfiguration = true, IncludeActivity = true }, TestContext.Current.CancellationToken);

        Assert.Contains("activity-recent.tsv", sink.Entries.Keys);
        var copy = Path.Combine(fx.Directory, "copy.db");
        await File.WriteAllBytesAsync(copy, sink.Entries["config.db"], TestContext.Current.CancellationToken);
        await using var connection = new SqliteConnection($"Data Source={copy};Pooling=False");
        await connection.OpenAsync(TestContext.Current.CancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = "PRAGMA user_version;";
        Assert.True(Convert.ToInt32(await command.ExecuteScalarAsync(TestContext.Current.CancellationToken), System.Globalization.CultureInfo.InvariantCulture) > 0);
    }

    private static StorageDiagnosticsContributor Create(StorageFixture fx) =>
        new(fx.Config, fx.Activity, new SqliteActivityQuery(fx.Activity), new FakeTimeProvider(DateTimeOffset.UtcNow));

    private sealed class MemorySink : IDiagnosticsSink
    {
        public Dictionary<string, byte[]> Entries { get; } = [];

        public void AddText(string path, string content) => Entries[path] = System.Text.Encoding.UTF8.GetBytes(content);

        public Task AddFileAsync(string path, string sourcePath, CancellationToken cancellationToken) => throw new NotSupportedException();

        public async Task AddBinaryAsync(string path, Stream content, CancellationToken cancellationToken)
        {
            using var buffer = new MemoryStream();
            await content.CopyToAsync(buffer, cancellationToken);
            Entries[path] = buffer.ToArray();
        }

        public void AddNote(string message)
        {
        }
    }
}
