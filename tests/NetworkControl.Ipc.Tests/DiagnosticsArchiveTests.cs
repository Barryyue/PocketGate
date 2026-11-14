using System.IO.Compression;
using System.Text;
using NetworkControl.Diagnostics;

namespace NetworkControl.Ipc.Tests;

public sealed class DiagnosticsArchiveTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "netcontrol-tests", Guid.NewGuid().ToString("N"));

    public DiagnosticsArchiveTests() => Directory.CreateDirectory(_directory);

    public void Dispose()
    {
        try
        {
            Directory.Delete(_directory, recursive: true);
        }
        catch (IOException)
        {
            // 臨時目錄
        }
    }

    private static CancellationToken Ct => TestContext.Current.CancellationToken;

    [Fact]
    public void Writes_redacted_text_and_a_manifest()
    {
        var zip = Build(a =>
        {
            a.AddText("app/summary.txt", @"Log directory C:\Users\zhangsan\AppData");
            a.AddNote(@"read C:\Users\zhangsan\x");
        });

        Assert.Equal(@"Log directory C:\Users\<user>\AppData", Read(zip, "app/summary.txt"));
        var manifest = Read(zip, DiagnosticsArchive.ManifestName);
        Assert.Contains("app/summary.txt", manifest, StringComparison.Ordinal);
        Assert.Contains(@"read C:\Users\<user>\x", manifest, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Reads_a_log_file_that_is_still_open_for_writing()
    {
        var path = Path.Combine(_directory, "service-1.log");
        // 模擬 Serilog（shared: true）持有寫句柄
        await using var writer = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.ReadWrite);
        await writer.WriteAsync(Encoding.UTF8.GetBytes("line 1\nline 2\n"), Ct);
        await writer.FlushAsync(Ct);

        var zip = await BuildAsync(a => a.AddFileAsync("logs/service-1.log", path, Ct));

        Assert.Equal("line 1\nline 2\n", Read(zip, "logs/service-1.log"));
    }

    [Fact]
    public async Task Keeps_the_tail_of_oversized_text_files()
    {
        var path = Path.Combine(_directory, "big.log");
        var lines = Enumerable.Range(1, 1000).Select(i => $"line {i:D4}");
        await File.WriteAllTextAsync(path, string.Join('\n', lines) + "\n", Ct);

        var zip = await BuildAsync(a => a.AddFileAsync("big.log", path, Ct), new DiagnosticsArchiveLimits { MaxTextFileBytes = 100 });

        var text = Read(zip, "big.log");
        Assert.StartsWith("[... truncated", text, StringComparison.Ordinal);
        Assert.EndsWith("line 1000\n", text, StringComparison.Ordinal);
        // 從完整的行開始，不會出現半行
        Assert.All(text.Split('\n', StringSplitOptions.RemoveEmptyEntries).Skip(1), l => Assert.Matches(@"^line \d{4}$", l));
    }

    [Fact]
    public async Task Copies_binary_files_unchanged()
    {
        var path = Path.Combine(_directory, "config.db");
        var bytes = new byte[] { 0x53, 0x51, 0x4C, 0x00, 0xFF, 0x0A };
        await File.WriteAllBytesAsync(path, bytes, Ct);

        var zip = await BuildAsync(a => a.AddFileAsync("data/config.db", path, Ct));

        using var archive = new ZipArchive(new MemoryStream(zip));
        using var stream = new MemoryStream();
        await using (var entry = await archive.GetEntry("data/config.db")!.OpenAsync(Ct))
        {
            await entry.CopyToAsync(stream, Ct);
        }

        Assert.Equal(bytes, stream.ToArray());
    }

    [Fact]
    public void Skips_content_beyond_the_total_size_limit_and_says_so()
    {
        var zip = Build(
            a =>
            {
                a.AddText("a.txt", new string('a', 60));
                a.AddText("b.txt", new string('b', 60));
            },
            new DiagnosticsArchiveLimits { MaxTotalBytes = 100 });

        using var archive = new ZipArchive(new MemoryStream(zip));
        Assert.NotNull(archive.GetEntry("a.txt"));
        Assert.Null(archive.GetEntry("b.txt"));
        Assert.Contains("[skipped] b.txt", Read(zip, DiagnosticsArchive.ManifestName), StringComparison.Ordinal);
    }

    [Fact]
    public async Task Merges_another_archive_under_a_prefix()
    {
        var inner = Build(a => a.AddText("logs/service.log", "hello"));

        var zip = await BuildAsync(a => a.MergeAsync(new MemoryStream(inner), "service", Ct));

        Assert.Equal("hello", Read(zip, "service/logs/service.log"));
        Assert.Contains("service/manifest.txt", Read(zip, DiagnosticsArchive.ManifestName), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(@"logs\a.log", "logs/a.log")]
    [InlineData("../../etc/passwd", "etc/passwd")]
    [InlineData(@"C:\Windows\x.txt", "Windows/x.txt")]
    [InlineData("/abs/./x.txt", "abs/x.txt")]
    [InlineData("..", "unnamed")]
    public void Normalizes_entry_paths_so_they_stay_inside_the_archive(string input, string expected) =>
        Assert.Equal(expected, DiagnosticsArchive.Normalize(input));

    [Fact]
    public void Disambiguates_duplicate_entry_names()
    {
        var zip = Build(a =>
        {
            a.AddText("x.txt", "1");
            a.AddText("x.txt", "2");
        });

        Assert.Equal("1", Read(zip, "x.txt"));
        Assert.Equal("2", Read(zip, "x-2.txt"));
    }

    private static byte[] Build(Action<DiagnosticsArchive> write, DiagnosticsArchiveLimits? limits = null) =>
        BuildAsync(a =>
        {
            write(a);
            return Task.CompletedTask;
        }, limits).GetAwaiter().GetResult();

    private static async Task<byte[]> BuildAsync(Func<DiagnosticsArchive, Task> write, DiagnosticsArchiveLimits? limits = null)
    {
        using var stream = new MemoryStream();
        using (var archive = new DiagnosticsArchive(stream, new DiagnosticsRedactor(["zhangsan"], "TESTMACHINE"), "test", limits))
        {
            await write(archive);
        }

        return stream.ToArray();
    }

    private static string Read(byte[] zip, string name)
    {
        using var archive = new ZipArchive(new MemoryStream(zip));
        var entry = archive.GetEntry(name) ?? throw new InvalidOperationException($"missing entry {name}");
        using var reader = new StreamReader(entry.Open(), Encoding.UTF8);
        return reader.ReadToEnd();
    }
}
