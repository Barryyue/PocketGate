using System.Globalization;
using System.Text.Json;

namespace NetworkControl.Engine.Vpn.Update;

public enum UpdateSource
{
    GitHub,
    GitLab,
    Gitee,
    Custom,
}

public sealed record UpdateInfo(
    string? LatestVersion,
    string? DownloadUrl,
    string? Error = null,
    string CurrentVersion = "0.0.0",
    string? FallbackDownloadUrl = null,
    string? ReleaseNotes = null)
{
    public bool HasUpdate =>
        LatestVersion is not null && AppUpdateChecker.CompareVersions(LatestVersion, CurrentVersion) > 0;
}

/// <summary>
/// 公網檢查更新（使用系統代理，不關閉 Proxy）：支援 GitHub / Gitee / 國內加速鏡像自動回退與 APK 下載校驗。
/// </summary>
public static class AppUpdateChecker
{
    public const string DefaultGitHubRepo = "Barryyue/PocketGate";
    public const string DefaultGiteeRepo = "yuexiaobai/PocketGate";
    public const string GitHubMirrorPrefix = "https://ghfast.top/";

    private const string GitHubReleases = "https://api.github.com/repos/{0}/releases?per_page=20";
    private const string GiteeReleases = "https://gitee.com/api/v5/repos/{0}/releases?page=1&per_page=20";

    public static async Task<UpdateInfo> CheckAutoAsync(
        string currentVersion,
        string githubRepo = DefaultGitHubRepo,
        string giteeRepo = DefaultGiteeRepo,
        CancellationToken ct = default)
    {
        using var client = CreateClient(currentVersion);
        var gh = await CheckSourceAsync(client, UpdateSource.GitHub, githubRepo, "", currentVersion, ct).ConfigureAwait(false);
        if (gh.Error is null && gh.LatestVersion is not null)
        {
            if (!gh.HasUpdate)
            {
                return gh;
            }

            var mirror = gh.DownloadUrl is { } u && u.StartsWith("https://github.com/", StringComparison.OrdinalIgnoreCase)
                ? GitHubMirrorPrefix + u
                : null;
            if (!string.IsNullOrEmpty(gh.DownloadUrl) && await IsReachableAsync(client, gh.DownloadUrl, ct).ConfigureAwait(false))
            {
                return gh with { FallbackDownloadUrl = mirror };
            }

            var gitee = await CheckSourceAsync(client, UpdateSource.Gitee, giteeRepo, "", currentVersion, ct).ConfigureAwait(false);
            if (gitee.Error is null && !string.IsNullOrEmpty(gitee.DownloadUrl))
            {
                if (string.Equals(gitee.LatestVersion, gh.LatestVersion, StringComparison.Ordinal))
                {
                    return gh with { DownloadUrl = gitee.DownloadUrl, FallbackDownloadUrl = mirror ?? gh.DownloadUrl };
                }

                if (gitee.HasUpdate)
                {
                    return gitee with { FallbackDownloadUrl = mirror ?? gh.DownloadUrl };
                }
            }

            return mirror is not null
                ? gh with { DownloadUrl = mirror, FallbackDownloadUrl = gh.DownloadUrl }
                : gh;
        }

        var giteeFallback = await CheckSourceAsync(client, UpdateSource.Gitee, giteeRepo, "", currentVersion, ct).ConfigureAwait(false);
        return giteeFallback.Error is null || gh.LatestVersion is null ? giteeFallback : gh;
    }

    public static async Task<string> DownloadApkAsync(
        string url,
        string targetPath,
        string? fallbackUrl,
        string currentVersion,
        Action<int>? onProgress = null,
        CancellationToken ct = default)
    {
        var candidates = new List<string> { url };
        if (!string.IsNullOrWhiteSpace(fallbackUrl) && !candidates.Contains(fallbackUrl, StringComparer.OrdinalIgnoreCase))
        {
            candidates.Add(fallbackUrl);
        }

        if (url.StartsWith("https://github.com/", StringComparison.OrdinalIgnoreCase))
        {
            var mirror = GitHubMirrorPrefix + url;
            if (!candidates.Contains(mirror, StringComparer.OrdinalIgnoreCase))
            {
                candidates.Add(mirror);
            }
        }

        using var client = CreateClient(currentVersion, TimeSpan.FromSeconds(60));
        Exception? lastError = null;
        foreach (var candidate in candidates)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                onProgress?.Invoke(0);
                return await DownloadSingleAsync(client, candidate, targetPath, onProgress, ct).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                lastError = ex;
            }
        }

        throw lastError ?? new IOException("All APK download candidates failed.");
    }

    public static UpdateInfo ParseReleaseJson(UpdateSource source, string body, string currentVersion)
    {
        using var doc = JsonDocument.Parse(body);
        var root = doc.RootElement;
        if (root.ValueKind == JsonValueKind.Array)
        {
            return ParseReleasesArray(source, root, currentVersion, requireAtomUi: true);
        }

        return ParseReleaseElement(source, root, currentVersion, requireAtomUi: false);
    }

    private static UpdateInfo ParseReleasesArray(UpdateSource source, JsonElement array, string currentVersion, bool requireAtomUi)
    {
        foreach (var item in array.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object) continue;
            if (item.TryGetProperty("draft", out var draft) && draft.ValueKind == JsonValueKind.True) continue;
            if (item.TryGetProperty("prerelease", out var pre) && pre.ValueKind == JsonValueKind.True) continue;

            var parsed = ParseReleaseElement(source, item, currentVersion, requireAtomUi);
            if (parsed.LatestVersion is not null)
            {
                return parsed;
            }
        }

        return new UpdateInfo(null, null, null, currentVersion);
    }

    private static UpdateInfo ParseReleaseElement(UpdateSource source, JsonElement root, string currentVersion, bool requireAtomUi)
    {
        var rawTag = source == UpdateSource.Custom
            ? FirstString(root, "version", "tag_name")
            : FirstString(root, "tag_name");
        var version = rawTag.Trim();
        var isAtomUiTag = version.StartsWith("atomui-v", StringComparison.OrdinalIgnoreCase);
        if (isAtomUiTag) version = version["atomui-v".Length..];
        else if (version.StartsWith("kt-v", StringComparison.OrdinalIgnoreCase)) version = version["kt-v".Length..];
        else if (version.StartsWith("v", StringComparison.OrdinalIgnoreCase)) version = version[1..];

        string? apkUrl = null;
        var hasAtomUiAsset = false;
        if (root.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
        {
            string? fallback = null;
            foreach (var asset in assets.EnumerateArray())
            {
                var u = FirstString(asset, "browser_download_url", "url", "direct_asset_url");
                if (!u.EndsWith(".apk", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                if (u.Contains("AtomUI", StringComparison.OrdinalIgnoreCase))
                {
                    apkUrl = u;
                    hasAtomUiAsset = true;
                    break;
                }

                if (!u.Contains("x86_64", StringComparison.OrdinalIgnoreCase) && !u.Contains("emulator", StringComparison.OrdinalIgnoreCase))
                {
                    fallback ??= u;
                }
            }

            if (!requireAtomUi)
            {
                apkUrl ??= fallback;
            }
        }

        // 只匹配 AtomUI 發行版（tag 以 atomui-v 開頭或資產包含 AtomUI），避免誤檢出 Kotlin 版
        if (requireAtomUi && !isAtomUiTag && !hasAtomUiAsset)
        {
            return new UpdateInfo(null, null, null, currentVersion);
        }

        var releaseUrl = FirstString(root, "html_url", "url");
        var notes = source switch
        {
            UpdateSource.GitLab => FirstString(root, "description"),
            UpdateSource.Custom => FirstString(root, "notes", "changelog"),
            _ => FirstString(root, "body"),
        };

        return new UpdateInfo(
            LatestVersion: string.IsNullOrWhiteSpace(version) ? null : version,
            DownloadUrl: apkUrl ?? (string.IsNullOrWhiteSpace(releaseUrl) ? null : releaseUrl),
            CurrentVersion: currentVersion,
            ReleaseNotes: string.IsNullOrWhiteSpace(notes) ? null : notes.Trim());
    }

    public static int CompareVersions(string a, string b)
    {
        var left = a.Split('.', '-').Select(s => int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var n) ? n : (int?)null).Where(n => n.HasValue).Select(n => n!.Value).ToArray();
        var right = b.Split('.', '-').Select(s => int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var n) ? n : (int?)null).Where(n => n.HasValue).Select(n => n!.Value).ToArray();
        var len = Math.Max(left.Length, right.Length);
        for (var i = 0; i < len; i++)
        {
            var l = i < left.Length ? left[i] : 0;
            var r = i < right.Length ? right[i] : 0;
            if (l != r) return l.CompareTo(r);
        }

        return 0;
    }

    private static async Task<UpdateInfo> CheckSourceAsync(
        HttpClient client,
        UpdateSource source,
        string repo,
        string customUrl,
        string currentVersion,
        CancellationToken ct)
    {
        var url = source switch
        {
            UpdateSource.GitHub => string.Format(CultureInfo.InvariantCulture, GitHubReleases, repo.Trim()),
            UpdateSource.Gitee => string.Format(CultureInfo.InvariantCulture, GiteeReleases, repo.Trim()),
            UpdateSource.Custom => customUrl.Trim(),
            _ => string.Empty,
        };
        if (string.IsNullOrEmpty(url))
        {
            return new UpdateInfo(null, null, "Invalid endpoint", currentVersion);
        }

        try
        {
            using var resp = await client.GetAsync(url, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode)
            {
                return new UpdateInfo(null, null, $"HTTP {(int)resp.StatusCode}", currentVersion);
            }

            var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            return ParseReleaseJson(source, body, currentVersion);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            return new UpdateInfo(null, null, ex.Message, currentVersion);
        }
    }

    private static async Task<bool> IsReachableAsync(HttpClient client, string url, CancellationToken ct)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Head, url);
            using var resp = await client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            return (int)resp.StatusCode < 400;
        }
        catch
        {
            return false;
        }
    }

    private static async Task<string> DownloadSingleAsync(
        HttpClient client,
        string url,
        string targetPath,
        Action<int>? onProgress,
        CancellationToken ct)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(targetPath)!);
        var partPath = targetPath + ".part";
        using var resp = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"HTTP {(int)resp.StatusCode}");
        }

        var total = resp.Content.Headers.ContentLength ?? -1L;
        var downloaded = 0L;
        var lastPercent = -1;
        await using (var src = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false))
        await using (var dst = new FileStream(partPath, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            var buf = new byte[16 * 1024];
            while (true)
            {
                var n = await src.ReadAsync(buf, ct).ConfigureAwait(false);
                if (n <= 0) break;
                await dst.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                downloaded += n;
                if (total > 0)
                {
                    var p = (int)Math.Clamp(downloaded * 100L / total, 0, 100);
                    if (p != lastPercent)
                    {
                        lastPercent = p;
                        onProgress?.Invoke(p);
                    }
                }
            }
        }

        var magic = new byte[4];
        await using (var verify = new FileStream(partPath, FileMode.Open, FileAccess.Read, FileShare.Read))
        {
            if (await verify.ReadAsync(magic, ct).ConfigureAwait(false) < 4 ||
                magic[0] != 0x50 || magic[1] != 0x4B || magic[2] != 0x03 || magic[3] != 0x04)
            {
                File.Delete(partPath);
                throw new InvalidDataException("Downloaded file is not a valid APK.");
            }
        }

        File.Move(partPath, targetPath, overwrite: true);
        onProgress?.Invoke(100);
        return targetPath;
    }

    private static HttpClient CreateClient(string currentVersion, TimeSpan? timeout = null)
    {
        var client = new HttpClient { Timeout = timeout ?? TimeSpan.FromSeconds(10) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd($"PocketGate-AtomUI/{currentVersion}");
        return client;
    }

    private static string FirstString(JsonElement obj, params string[] names)
    {
        foreach (var name in names)
        {
            if (obj.TryGetProperty(name, out var prop) && prop.ValueKind == JsonValueKind.String)
            {
                var s = prop.GetString();
                if (!string.IsNullOrEmpty(s)) return s;
            }
        }

        return string.Empty;
    }
}
