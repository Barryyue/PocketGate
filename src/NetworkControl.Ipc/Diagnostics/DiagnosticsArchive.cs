using System.Globalization;
using System.IO.Compression;
using System.Text;

namespace NetworkControl.Ipc.Diagnostics;

/// <summary>診斷包的大小上限（未壓縮字節數）。</summary>
public sealed record DiagnosticsArchiveLimits
{
    /// <summary>整個診斷包的上限；超出後後續內容只記錄在清單中，不再寫入。</summary>
    public long MaxTotalBytes { get; init; } = 96L * 1024 * 1024;

    /// <summary>單個文本文件（日誌）的上限；超出時保留末尾（最新的）部分。</summary>
    public long MaxTextFileBytes { get; init; } = 8L * 1024 * 1024;
}

/// <summary>
/// 診斷包（zip）寫入器：文本統一脫敏、日誌按上限截取末尾、可以讀取正被寫入的日誌文件，
/// 並在結束時生成 <c>manifest.txt</c>（條目清單 + 收集過程中的說明和錯誤）。
/// 不是線程安全的：收集者依次調用。
/// </summary>
public sealed class DiagnosticsArchive : IDisposable
{
    public const string ManifestName = "manifest.txt";

    private static readonly HashSet<string> TextExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".log", ".txt", ".json", ".xml", ".csv", ".tsv", ".ini", ".config",
    };

    private static readonly UTF8Encoding Utf8 = new(encoderShouldEmitUTF8Identifier: false);

    private readonly ZipArchive _zip;
    private readonly DiagnosticsRedactor _redactor;
    private readonly DiagnosticsArchiveLimits _limits;
    private readonly List<(string Path, long Bytes)> _entries = [];
    private readonly List<string> _notes = [];
    private readonly HashSet<string> _names = new(StringComparer.OrdinalIgnoreCase);
    private readonly string _title;
    private long _totalBytes;
    private bool _disposed;

    public DiagnosticsArchive(Stream destination, DiagnosticsRedactor redactor, string title, DiagnosticsArchiveLimits? limits = null)
    {
        _zip = new ZipArchive(destination, ZipArchiveMode.Create, leaveOpen: true, entryNameEncoding: Utf8);
        _redactor = redactor;
        _title = title;
        _limits = limits ?? new DiagnosticsArchiveLimits();
    }

    public IReadOnlyList<string> Entries => _entries.ConvertAll(e => e.Path);

    public IReadOnlyList<string> Notes => _notes;

    public DiagnosticsRedactor Redactor => _redactor;

    public void AddText(string path, string content)
    {
        var bytes = Utf8.GetBytes(_redactor.Redact(content));
        if (Reserve(path, bytes.Length) is { } name)
        {
            using var stream = _zip.CreateEntry(name, CompressionLevel.Optimal).Open();
            stream.Write(bytes);
        }
    }

    /// <summary>
    /// 複製文件。文本文件（按擴展名判斷）脫敏並按上限截取末尾；其他文件原樣複製。
    /// 以共享讀寫方式打開，正在被 Serilog 等寫入的日誌也能讀取。
    /// </summary>
    public async Task AddFileAsync(string path, string sourcePath, CancellationToken cancellationToken)
    {
        await using var source = new FileStream(
            sourcePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 81920, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (!TextExtensions.Contains(Path.GetExtension(sourcePath)))
        {
            await AddBinaryAsync(path, source, cancellationToken);
            return;
        }

        var length = source.Length;
        var skipped = 0L;
        if (length > _limits.MaxTextFileBytes)
        {
            skipped = length - _limits.MaxTextFileBytes;
            source.Seek(skipped, SeekOrigin.Begin);
        }

        using var reader = new StreamReader(source, Utf8, detectEncodingFromByteOrderMarks: true);
        var text = await reader.ReadToEndAsync(cancellationToken);
        if (skipped > 0)
        {
            // 從截斷處的下一行開始，避免半行和被截斷的多字節字符
            var newline = text.IndexOf('\n', StringComparison.Ordinal);
            text = string.Create(CultureInfo.InvariantCulture, $"[... truncated {skipped:N0} bytes from the beginning ...]\n{(newline >= 0 ? text[(newline + 1)..] : text)}");
        }

        AddText(path, text);
    }

    public Task AddBinaryAsync(string path, Stream content, CancellationToken cancellationToken) =>
        AddBinaryAsync(path, content, content.CanSeek ? content.Length - content.Position : null, cancellationToken);

    private async Task AddBinaryAsync(string path, Stream content, long? length, CancellationToken cancellationToken)
    {
        if (Reserve(path, length ?? 0) is not { } name)
        {
            return;
        }

        await using var stream = _zip.CreateEntry(name, CompressionLevel.Optimal).Open();
        var counting = new CountingStream(stream);
        await content.CopyToAsync(counting, cancellationToken);
        if (length is null)
        {
            _totalBytes += counting.Written;
            _entries[^1] = (_entries[^1].Path, counting.Written);
        }
    }

    /// <summary>把另一個診斷包（例如服務生成的）的全部條目合併到 <paramref name="prefix"/> 目錄下。內容已由生成方脫敏。</summary>
    public async Task MergeAsync(Stream zip, string prefix, CancellationToken cancellationToken)
    {
        using var source = new ZipArchive(zip, ZipArchiveMode.Read, leaveOpen: true, entryNameEncoding: Utf8);
        foreach (var entry in source.Entries)
        {
            if (entry.FullName.EndsWith('/'))
            {
                continue;
            }

            await using var input = entry.Open();
            await AddBinaryAsync(Combine(prefix, entry.FullName), input, entry.Length, cancellationToken);
        }
    }

    public void AddNote(string message) => _notes.Add(_redactor.Redact(message));

    /// <summary>記錄某個部分收集失敗；不中斷整個導出。</summary>
    public void AddError(string section, Exception exception) =>
        AddNote($"[error] {section}: {exception.GetType().Name}: {exception.Message}");

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        try
        {
            WriteManifest();
        }
        finally
        {
            _zip.Dispose();
        }
    }

    internal static string Combine(string prefix, string path) =>
        string.IsNullOrEmpty(prefix) ? path : $"{prefix.TrimEnd('/')}/{path.TrimStart('/')}";

    private void WriteManifest()
    {
        var text = new StringBuilder()
            .AppendLine(_title)
            .AppendLine(CultureInfo.InvariantCulture, $"Created (UTC): {DateTime.UtcNow:yyyy-MM-dd HH:mm:ss}")
            .AppendLine(CultureInfo.InvariantCulture, $"Uncompressed size: {_totalBytes:N0} bytes")
            .AppendLine()
            .AppendLine("Entries:");
        foreach (var (path, bytes) in _entries)
        {
            text.AppendLine(CultureInfo.InvariantCulture, $"  {path} ({bytes:N0} bytes)");
        }

        if (_notes.Count > 0)
        {
            text.AppendLine().AppendLine("Notes:");
            foreach (var note in _notes)
            {
                text.AppendLine(CultureInfo.InvariantCulture, $"  - {note}");
            }
        }

        // 清單本身不計入大小上限，總是寫入
        using var stream = _zip.CreateEntry(UniqueName(ManifestName), CompressionLevel.Optimal).Open();
        stream.Write(Utf8.GetBytes(text.ToString()));
    }

    /// <summary>檢查大小上限並登記條目；超出上限時返回 null 並記錄說明。</summary>
    private string? Reserve(string path, long bytes)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        var name = UniqueName(Normalize(path));
        if (_totalBytes + bytes > _limits.MaxTotalBytes)
        {
            _notes.Add(string.Create(CultureInfo.InvariantCulture, $"[skipped] {name}: size limit reached ({bytes:N0} bytes)"));
            return null;
        }

        _totalBytes += bytes;
        _entries.Add((name, bytes));
        return name;
    }

    private string UniqueName(string name)
    {
        var candidate = name;
        for (var i = 2; !_names.Add(candidate); i++)
        {
            var extension = Path.GetExtension(name);
            candidate = string.Create(CultureInfo.InvariantCulture, $"{name[..^extension.Length]}-{i}{extension}");
        }

        return candidate;
    }

    /// <summary>統一為正斜槓，去掉「..」和根，防止條目跳出診斷包目錄。</summary>
    internal static string Normalize(string path)
    {
        var parts = path.Replace('\\', '/').Split('/', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(p => p is not "." and not ".." && !p.EndsWith(':'));
        var result = string.Join('/', parts);
        return result.Length == 0 ? "unnamed" : result;
    }

    private sealed class CountingStream(Stream inner) : Stream
    {
        public long Written { get; private set; }

        public override bool CanRead => false;

        public override bool CanSeek => false;

        public override bool CanWrite => true;

        public override long Length => throw new NotSupportedException();

        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override void Flush() => inner.Flush();

        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count)
        {
            inner.Write(buffer, offset, count);
            Written += count;
        }

        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
        {
            await inner.WriteAsync(buffer, cancellationToken);
            Written += buffer.Length;
        }

        public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
            WriteAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();
    }
}
