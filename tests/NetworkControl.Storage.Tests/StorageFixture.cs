using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Tests;

/// <summary>每個測試一個獨立的臨時數據目錄，測試結束後刪除。</summary>
public sealed class StorageFixture : IAsyncDisposable
{
    private StorageFixture(string directory)
    {
        Directory = directory;
        Options = Microsoft.Extensions.Options.Options.Create(new StorageOptions
        {
            DataDirectory = directory,
            ActivityFlushInterval = TimeSpan.FromMilliseconds(50),
        });
        Config = new ConfigDatabase(Options);
        Activity = new ActivityDatabase(Options);
    }

    public string Directory { get; }

    public IOptions<StorageOptions> Options { get; }

    public ConfigDatabase Config { get; }

    public ActivityDatabase Activity { get; }

    public static async Task<StorageFixture> CreateAsync()
    {
        var dir = Path.Combine(Path.GetTempPath(), "netcontrol-tests", Guid.NewGuid().ToString("N"));
        System.IO.Directory.CreateDirectory(dir);
        var fixture = new StorageFixture(dir);
        await Migrator.MigrateAsync(fixture.Config, NullLogger.Instance, TestContext.Current.CancellationToken);
        await Migrator.MigrateAsync(fixture.Activity, NullLogger.Instance, TestContext.Current.CancellationToken);
        return fixture;
    }

    public ValueTask DisposeAsync()
    {
        // 釋放連接池中的文件句柄，否則 Windows 上無法刪除數據庫文件
        SqliteConnection.ClearAllPools();
        try
        {
            System.IO.Directory.Delete(Directory, recursive: true);
        }
        catch (IOException)
        {
            // 臨時目錄，刪除失敗不影響測試結果
        }

        return ValueTask.CompletedTask;
    }
}
