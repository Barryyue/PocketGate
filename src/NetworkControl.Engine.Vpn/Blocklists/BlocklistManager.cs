using System.Text.Json;

namespace NetworkControl.Engine.Vpn.Blocklists;

public sealed class BlocklistSourceState
{
    public string Id { get; set; } = string.Empty;
    public string Name { get; set; } = string.Empty;
    public List<string> Urls { get; set; } = [];
    public bool IsBuiltin { get; set; }
    public bool Enabled { get; set; }
    public int EntryCount { get; set; }
    public DateTimeOffset? LastUpdatedUtc { get; set; }
    public string? LastError { get; set; }
}

/// <summary>
/// 網域攔截清單管理器：維護 7 份內建清單與自訂 URL 訂閱、本地快取、離線模式守衛與合併後的 <see cref="DomainBlocklist"/> 快照。
/// </summary>
public sealed class BlocklistManager
{
    private readonly string _dir;
    private readonly string _metaPath;
    private readonly object _gate = new();
    private readonly List<BlocklistSourceState> _sources = [];
    private volatile DomainBlocklist? _activeList;

    public event EventHandler? Changed;

    public BlocklistManager(string storageDir)
    {
        _dir = Path.Combine(storageDir, "blocklists");
        _metaPath = Path.Combine(_dir, "sources.json");
        Directory.CreateDirectory(_dir);
        LoadState();
        RebuildActiveList();
    }

    public DomainBlocklist? ActiveList => _activeList;

    public int TotalEntries
    {
        get
        {
            lock (_gate)
            {
                return _sources.Where(s => s.Enabled).Sum(s => s.EntryCount);
            }
        }
    }

    public int EnabledCount
    {
        get
        {
            lock (_gate)
            {
                return _sources.Count(s => s.Enabled);
            }
        }
    }

    public IReadOnlyList<BlocklistSourceState> GetSources()
    {
        lock (_gate)
        {
            return _sources.Select(s => new BlocklistSourceState
            {
                Id = s.Id,
                Name = s.Name,
                Urls = [.. s.Urls],
                IsBuiltin = s.IsBuiltin,
                Enabled = s.Enabled,
                EntryCount = s.EntryCount,
                LastUpdatedUtc = s.LastUpdatedUtc,
                LastError = s.LastError,
            }).ToList();
        }
    }

    public void SetSourceEnabled(string id, bool enabled)
    {
        lock (_gate)
        {
            var src = _sources.FirstOrDefault(s => string.Equals(s.Id, id, StringComparison.Ordinal));
            if (src is null || src.Enabled == enabled) return;
            src.Enabled = enabled;
            SaveStateLocked();
        }

        RebuildActiveList();
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public string AddCustomSource(string name, string url)
    {
        var trimmedUrl = url.Trim();
        if (!Uri.TryCreate(trimmedUrl, UriKind.Absolute, out var uri) ||
            (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            throw new ArgumentException("Invalid blocklist URL.");
        }

        var id = $"custom-{Guid.NewGuid():N}"[..16];
        var label = string.IsNullOrWhiteSpace(name) ? uri.Host : name.Trim();
        lock (_gate)
        {
            _sources.Add(new BlocklistSourceState
            {
                Id = id,
                Name = label,
                Urls = [trimmedUrl],
                IsBuiltin = false,
                Enabled = true,
            });
            SaveStateLocked();
        }

        Changed?.Invoke(this, EventArgs.Empty);
        return id;
    }

    public void RemoveCustomSource(string id)
    {
        lock (_gate)
        {
            var idx = _sources.FindIndex(s => !s.IsBuiltin && string.Equals(s.Id, id, StringComparison.Ordinal));
            if (idx < 0) return;
            _sources.RemoveAt(idx);
            var cacheFile = CacheFileFor(id);
            if (File.Exists(cacheFile))
            {
                try { File.Delete(cacheFile); } catch { }
            }

            SaveStateLocked();
        }

        RebuildActiveList();
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public async Task<(int Updated, int Failed)> UpdateAllAsync(bool offlineMode, CancellationToken cancellationToken)
    {
        if (offlineMode)
        {
            throw new InvalidOperationException("offline_mode");
        }

        var targets = GetSources().Where(s => s.Enabled).ToList();
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(25) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("PocketGate/1.0");

        var updated = 0;
        var failed = 0;
        foreach (var src in targets)
        {
            cancellationToken.ThrowIfCancellationRequested();
            string? content = null;
            string? error = null;
            foreach (var url in src.Urls)
            {
                try
                {
                    using var resp = await client.GetAsync(url, cancellationToken).ConfigureAwait(false);
                    resp.EnsureSuccessStatusCode();
                    content = await resp.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
                    error = null;
                    break;
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    error = ex.Message;
                }
            }

            if (content is not null)
            {
                var parsed = DomainBlocklist.Parse(ReadLines(content));
                await File.WriteAllTextAsync(CacheFileFor(src.Id), content, cancellationToken).ConfigureAwait(false);
                lock (_gate)
                {
                    var target = _sources.FirstOrDefault(s => s.Id == src.Id);
                    if (target is not null)
                    {
                        target.EntryCount = parsed.List.Size;
                        target.LastUpdatedUtc = DateTimeOffset.UtcNow;
                        target.LastError = null;
                    }
                }

                updated++;
            }
            else
            {
                lock (_gate)
                {
                    var target = _sources.FirstOrDefault(s => s.Id == src.Id);
                    if (target is not null)
                    {
                        target.LastError = error ?? "Download failed";
                    }
                }

                failed++;
            }
        }

        lock (_gate)
        {
            SaveStateLocked();
        }

        RebuildActiveList();
        Changed?.Invoke(this, EventArgs.Empty);
        return (updated, failed);
    }

    private void RebuildActiveList()
    {
        List<BlocklistSourceState> enabled;
        lock (_gate)
        {
            enabled = _sources.Where(s => s.Enabled).ToList();
        }

        if (enabled.Count == 0)
        {
            _activeList = null;
            return;
        }

        var lines = new List<string>();
        foreach (var src in enabled)
        {
            var path = CacheFileFor(src.Id);
            if (!File.Exists(path)) continue;
            try
            {
                lines.AddRange(File.ReadLines(path));
            }
            catch
            {
            }
        }

        _activeList = lines.Count > 0 ? DomainBlocklist.Parse(lines).List : null;
    }

    private void LoadState()
    {
        lock (_gate)
        {
            var saved = new Dictionary<string, BlocklistSourceState>(StringComparer.Ordinal);
            if (File.Exists(_metaPath))
            {
                try
                {
                    var json = File.ReadAllText(_metaPath);
                    var items = JsonSerializer.Deserialize<List<BlocklistSourceState>>(json) ?? [];
                    foreach (var item in items)
                    {
                        saved[item.Id] = item;
                    }
                }
                catch
                {
                }
            }

            _sources.Clear();
            foreach (var b in BlocklistCatalog.Builtins)
            {
                saved.TryGetValue(b.Id, out var prev);
                _sources.Add(new BlocklistSourceState
                {
                    Id = b.Id,
                    Name = b.Name,
                    Urls = [.. b.Urls],
                    IsBuiltin = true,
                    Enabled = prev?.Enabled ?? b.DefaultEnabled,
                    EntryCount = prev?.EntryCount ?? 0,
                    LastUpdatedUtc = prev?.LastUpdatedUtc,
                    LastError = prev?.LastError,
                });
            }

            foreach (var item in saved.Values.Where(s => !s.IsBuiltin))
            {
                _sources.Add(item);
            }
        }
    }

    private void SaveStateLocked()
    {
        try
        {
            var json = JsonSerializer.Serialize(_sources);
            File.WriteAllText(_metaPath, json);
        }
        catch
        {
        }
    }

    private string CacheFileFor(string id) => Path.Combine(_dir, $"{id}.txt");

    private static IEnumerable<string> ReadLines(string content)
    {
        using var reader = new StringReader(content);
        while (reader.ReadLine() is { } line)
        {
            yield return line;
        }
    }
}
