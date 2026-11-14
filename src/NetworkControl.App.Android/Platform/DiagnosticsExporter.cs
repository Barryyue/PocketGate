using System.Globalization;
using System.Reflection;
using System.Text;
using NetworkControl.Android.Hosting;
using NetworkControl.Core.Domain;
using NetworkControl.Diagnostics;
using NetworkControl.Engine.Vpn.Routing;
using Build = global::Android.OS.Build;

namespace NetworkControl.Android.Platform;

/// <summary>診斷導出的結果。<see cref="Canceled"/> 為 true 表示用戶在系統文件選擇器中取消。</summary>
public sealed record DiagnosticsExportResult(bool Saved, bool Canceled, string FileName, long Bytes, string? Error);

/// <summary>
/// 診斷資訊導出（docs/m3-tasks.md M3-11）：在應用私有目錄生成 zip，再經 SAF 保存到用戶選擇的位置。
/// 內容：summary.txt（版本、設備、保護狀態、引擎計數器、路由計劃摘要）、logs/（最近 3 個日誌）、
/// rules-stats.txt（按來源 / 動作統計，默認不含包名）、traffic-access.txt（使用情況存取權狀態）。
/// 所有文本經 <see cref="DiagnosticsRedactor"/> 脫敏；Android 上額外把 IP 地址替換為 &lt;ip&gt;。
/// </summary>
public static class DiagnosticsExporter
{
    private const int MaxLogFiles = 3;
    private const string ZipMimeType = "application/zip";

    public static async Task<DiagnosticsExportResult> ExportAsync(IBackend backend, bool includeAppList, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(backend);

        var context = global::Android.App.Application.Context;
        var fileName = $"networkcontrol-diagnostics-{DateTime.Now.ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture)}.zip";
        var directory = context.CacheDir?.AbsolutePath ?? Path.GetTempPath();
        Directory.CreateDirectory(directory);
        var temporary = Path.Combine(directory, fileName);
        try
        {
            await WriteAsync(context, temporary, backend, includeAppList, cancellationToken);
            var bytes = new FileInfo(temporary).Length;

            var activity = MainActivity.Current;
            if (activity is null)
            {
                return new DiagnosticsExportResult(false, false, fileName, 0, "The app UI is not available.");
            }

            var uri = await activity.RequestCreateDocumentAsync(fileName, ZipMimeType, cancellationToken);
            if (uri is null)
            {
                return new DiagnosticsExportResult(false, true, fileName, 0, null);
            }

            await using var output = context.ContentResolver?.OpenOutputStream(uri, "w")
                ?? throw new IOException("Cannot open the selected file.");
            await using var input = new FileStream(temporary, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, FileOptions.Asynchronous | FileOptions.SequentialScan);
            await input.CopyToAsync(output, cancellationToken);
            return new DiagnosticsExportResult(true, false, fileName, bytes, null);
        }
        finally
        {
            try
            {
                File.Delete(temporary);
            }
            catch (IOException)
            {
                // 快取目錄由系統清理
            }
        }
    }

    private static async Task WriteAsync(
        global::Android.Content.Context context,
        string path,
        IBackend backend,
        bool includeAppList,
        CancellationToken cancellationToken)
    {
        var version = typeof(DiagnosticsExporter).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "0.0.0";
        await using var stream = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None, 81920, FileOptions.Asynchronous);
        using var archive = new DiagnosticsArchive(stream, new DiagnosticsRedactor(redactAddresses: true), $"NetworkControl Android diagnostics ({version})");

        archive.AddText("summary.txt", BuildSummary(context, backend, version));
        archive.AddText("rules-stats.txt", BuildRuleStats(backend, includeAppList));
        archive.AddText("traffic-access.txt", BuildTrafficAccess(backend));

        foreach (var log in RecentLogs(context))
        {
            cancellationToken.ThrowIfCancellationRequested();
            await archive.AddFileAsync($"logs/{log.Name}", log.FullName, cancellationToken);
        }
    }

    private static string BuildSummary(global::Android.Content.Context context, IBackend backend, string version)
    {
        var counters = backend.Counters;
        var apps = backend.GetApps();
        var allowed = apps.Count(a => a.Effective == AppRoutingDecision.Bypass);
        var blocked = apps.Count(a => a.Effective == AppRoutingDecision.Block);
        var ask = apps.Count(a => a.Effective == AppRoutingDecision.BlockAndAsk);
        var prepared = global::Android.Net.VpnService.Prepare(context) is null;

        var text = new StringBuilder()
            .AppendLine("NetworkControl Android diagnostics")
            .AppendLine(CultureInfo.InvariantCulture, $"Generated (UTC): {DateTime.UtcNow:yyyy-MM-dd HH:mm:ss}")
            .AppendLine(CultureInfo.InvariantCulture, $"App version: {version}")
            .AppendLine(CultureInfo.InvariantCulture, $"Android: {Build.VERSION.Release} (API {(int)Build.VERSION.SdkInt})")
            .AppendLine(CultureInfo.InvariantCulture, $"Device: {Build.Manufacturer} {Build.Model} ({Build.Device})")
            .AppendLine(CultureInfo.InvariantCulture, $"ABIs: {string.Join(", ", Build.SupportedAbis ?? [])}")
            .AppendLine()
            .AppendLine(CultureInfo.InvariantCulture, $"Protection: {backend.Protection}")
            .AppendLine(CultureInfo.InvariantCulture, $"Protection error: {backend.ProtectionError ?? "-"}")
            .AppendLine(CultureInfo.InvariantCulture, $"Mode: {backend.Mode}")
            .AppendLine(CultureInfo.InvariantCulture, $"Active profile: {backend.ActiveProfile.Name} ({backend.Profiles.Count} profiles)")
            .AppendLine(CultureInfo.InvariantCulture, $"VPN authorized: {(prepared ? "yes" : "no")}")
            .AppendLine(CultureInfo.InvariantCulture, $"Notifications enabled: {(MainActivity.Current?.AreNotificationsEnabled == true ? "yes" : "no")}")
            .AppendLine(CultureInfo.InvariantCulture, $"Usage access: {(backend.HasTrafficAccess ? "granted" : "not granted")}")
            .AppendLine()
            .AppendLine("Engine counters:")
            .AppendLine(CultureInfo.InvariantCulture, $"  packets: {counters.Packets}")
            .AppendLine(CultureInfo.InvariantCulture, $"  unparsed: {counters.Unparsed}")
            .AppendLine(CultureInfo.InvariantCulture, $"  attempts: {counters.Attempts}")
            .AppendLine(CultureInfo.InvariantCulture, $"  queue full: {counters.QueueFull}")
            .AppendLine(CultureInfo.InvariantCulture, $"  unknown owner: {counters.UnknownOwner}")
            .AppendLine(CultureInfo.InvariantCulture, $"  deduplicated: {counters.Deduplicated}")
            .AppendLine(CultureInfo.InvariantCulture, $"  reported: {counters.Reported}")
            .AppendLine(CultureInfo.InvariantCulture, $"  dns answered: {counters.DnsAnswered}")
            .AppendLine(CultureInfo.InvariantCulture, $"  system uid: {counters.SystemUid}")
            .AppendLine(CultureInfo.InvariantCulture, $"  TCP RST sent: {counters.ResetsSent}")
            .AppendLine(CultureInfo.InvariantCulture, $"  ICMP unreachable sent: {counters.UnreachablesSent}")
            .AppendLine()
            .AppendLine("Routing plan:")
            .AppendLine(CultureInfo.InvariantCulture, $"  apps: {apps.Count}")
            .AppendLine(CultureInfo.InvariantCulture, $"  allowed: {allowed}")
            .AppendLine(CultureInfo.InvariantCulture, $"  blocked: {blocked}")
            .AppendLine(CultureInfo.InvariantCulture, $"  ask: {ask}")
            .AppendLine(CultureInfo.InvariantCulture, $"  skipped rules: {backend.SkippedRuleCount}")
            .AppendLine(CultureInfo.InvariantCulture, $"  pending prompts: {backend.PendingPromptCount}");

        return text.ToString();
    }

    private static string BuildRuleStats(IBackend backend, bool includeAppList)
    {
        var rules = backend.Rules;
        var text = new StringBuilder()
            .AppendLine("Rule statistics")
            .AppendLine(CultureInfo.InvariantCulture, $"Total: {rules.Count}")
            .AppendLine(CultureInfo.InvariantCulture, $"Shared: {rules.Count(r => r.ProfileId is null)}")
            .AppendLine(CultureInfo.InvariantCulture, $"Profile-specific: {rules.Count(r => r.ProfileId is not null)}")
            .AppendLine()
            .AppendLine("By action:");

        foreach (var group in rules.GroupBy(r => r.Action).OrderBy(g => g.Key.ToString(), StringComparer.Ordinal))
        {
            text.AppendLine(CultureInfo.InvariantCulture, $"  {group.Key}: {group.Count()}");
        }

        text.AppendLine().AppendLine("By source:");
        foreach (var group in rules.GroupBy(r => r.Source).OrderBy(g => g.Key.ToString(), StringComparer.Ordinal))
        {
            text.AppendLine(CultureInfo.InvariantCulture, $"  {group.Key}: {group.Count()}");
        }

        text.AppendLine().AppendLine("By lifetime:");
        foreach (var group in rules.GroupBy(r => r.Lifetime).OrderBy(g => g.Key.ToString(), StringComparer.Ordinal))
        {
            text.AppendLine(CultureInfo.InvariantCulture, $"  {group.Key}: {group.Count()}");
        }

        var skipped = rules
            .Select(rule => (Rule: rule, Reason: VpnRoutingPlanner.GetSkipReason(rule)))
            .Where(pair => pair.Reason is not null)
            .GroupBy(pair => pair.Reason!.Value)
            .OrderBy(g => g.Key.ToString(), StringComparer.Ordinal)
            .ToArray();
        text.AppendLine().AppendLine("Not enforced on Android:");
        if (skipped.Length == 0)
        {
            text.AppendLine("  none");
        }
        else
        {
            foreach (var group in skipped)
            {
                text.AppendLine(CultureInfo.InvariantCulture, $"  {group.Key}: {group.Count()}");
            }
        }

        if (includeAppList)
        {
            text.AppendLine().AppendLine("Applications:");
            foreach (var app in backend.GetApps().OrderBy(a => a.App.PackageName, StringComparer.Ordinal))
            {
                text.AppendLine(CultureInfo.InvariantCulture, $"  {app.App.PackageName}\t{app.Decision}\t{app.Effective}\t{app.Reason}\t{app.App.Label}");
            }
        }
        else
        {
            text.AppendLine().AppendLine("Applications: not included (enable \"include app list\" before exporting).");
        }

        return text.ToString();
    }

    private static string BuildTrafficAccess(IBackend backend)
    {
        var status = backend.HasTrafficAccess ? "granted" : "not granted";
        return $"Usage access: {status}{System.Environment.NewLine}" +
               "NetworkControl reads Android's per-app network statistics (NetworkStatsManager) for the data usage page." +
               System.Environment.NewLine;
    }

    private static IReadOnlyList<FileInfo> RecentLogs(global::Android.Content.Context context)
    {
        var directory = Path.Combine(context.FilesDir?.AbsolutePath ?? Path.GetTempPath(), "logs");
        if (!Directory.Exists(directory))
        {
            return [];
        }

        try
        {
            return [.. new DirectoryInfo(directory).EnumerateFiles("*.log").OrderByDescending(f => f.LastWriteTimeUtc).Take(MaxLogFiles)];
        }
        catch (IOException)
        {
            return [];
        }
    }
}
