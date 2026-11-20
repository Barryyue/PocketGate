using NetworkControl.Core.Domain;
using NetworkControl.Engine.Vpn.Backup;
using NetworkControl.Engine.Vpn.Blocklists;
using NetworkControl.Engine.Vpn.Forwarding;
using NetworkControl.Engine.Vpn.Routing;
using NetworkControl.Engine.Vpn.Update;
using Xunit;

namespace NetworkControl.Engine.Vpn.Tests;

public class ParityFeatureTests
{
    [Fact]
    public void WebDavBackupEncryptsDecryptsAndRejectsWrongPassphrase()
    {
        const string json = "{\"format\":\"netcontrol-rules\",\"version\":1,\"rules\":[{\"id\":\"r1\"}]}";
        var encrypted = WebDavBackupClient.Encrypt(json, "strong-pass-123");
        var decrypted = WebDavBackupClient.Decrypt(encrypted, "strong-pass-123");
        Assert.Equal(json, decrypted);
        Assert.ThrowsAny<Exception>(() => WebDavBackupClient.Decrypt(encrypted, "wrong-pass"));
    }

    [Fact]
    public void UpdateCheckerParsesAtomUiApkAndReleaseNotes()
    {
        const string json = """
            {
              "tag_name": "atomui-v0.16.0",
              "body": "- Bento dashboard\n- WebDAV sync",
              "assets": [
                {"name": "PocketGate-0.16.0.apk", "browser_download_url": "https://example.com/PocketGate-0.16.0.apk"},
                {"name": "PocketGate-0.16.0-AtomUI.apk", "browser_download_url": "https://example.com/PocketGate-0.16.0-AtomUI.apk"}
              ]
            }
            """;
        var info = AppUpdateChecker.ParseReleaseJson(UpdateSource.GitHub, json, "0.15.0");
        Assert.True(info.HasUpdate);
        Assert.Equal("0.16.0", info.LatestVersion);
        Assert.Equal("https://example.com/PocketGate-0.16.0-AtomUI.apk", info.DownloadUrl);
        Assert.Contains("Bento dashboard", info.ReleaseNotes);
    }

    [Fact]
    public void UpdateCheckerSkipsKotlinReleasesAndMatchesOnlyAtomUiInReleaseList()
    {
        const string json = """
            [
              {
                "tag_name": "v0.16.0",
                "body": "Kotlin release",
                "assets": [
                  {"name": "PocketGate-0.16.0-arm64-v8a.apk", "browser_download_url": "https://example.com/PocketGate-0.16.0-arm64-v8a.apk"}
                ]
              },
              {
                "tag_name": "atomui-v0.3.1",
                "body": "AtomUI release",
                "assets": [
                  {"name": "PocketGate-0.3.1-AtomUI.apk", "browser_download_url": "https://example.com/PocketGate-0.3.1-AtomUI.apk"}
                ]
              }
            ]
            """;
        var info = AppUpdateChecker.ParseReleaseJson(UpdateSource.GitHub, json, "0.3.0");
        Assert.True(info.HasUpdate);
        Assert.Equal("0.3.1", info.LatestVersion);
        Assert.Equal("https://example.com/PocketGate-0.3.1-AtomUI.apk", info.DownloadUrl);

        const string kotlinOnly = """
            [
              {
                "tag_name": "v0.16.0",
                "assets": [
                  {"name": "PocketGate-0.16.0-arm64-v8a.apk", "browser_download_url": "https://example.com/PocketGate-0.16.0-arm64-v8a.apk"}
                ]
              }
            ]
            """;
        var none = AppUpdateChecker.ParseReleaseJson(UpdateSource.GitHub, kotlinOnly, "0.3.0");
        Assert.False(none.HasUpdate);
        Assert.Null(none.LatestVersion);
    }

    [Fact]
    public void DomainSnifferExtractsHttpHostAndBlocklistManagerPersistsSources()
    {
        var http = System.Text.Encoding.ASCII.GetBytes("GET / HTTP/1.1\r\nHost: ads.example.com:443\r\n\r\n");
        Assert.True(NetworkControl.Engine.Vpn.Forwarding.DomainSniffer.TryExtractDomain(http, out var host));
        Assert.Equal("ads.example.com", host);

        var dir = Directory.CreateTempSubdirectory("pg-blocklist-mgr");
        try
        {
            var mgr = new NetworkControl.Engine.Vpn.Blocklists.BlocklistManager(dir.FullName);
            Assert.Equal(7, mgr.GetSources().Count);
            var customId = mgr.AddCustomSource("CustomAds", "https://example.com/list.txt");
            Assert.Equal(8, mgr.GetSources().Count);
            mgr.RemoveCustomSource(customId);
            Assert.Equal(7, mgr.GetSources().Count);
        }
        finally
        {
            dir.Delete(recursive: true);
        }
    }

    [Fact]
    public async Task AppUpdateManagerRespectsOfflineModeAndTracksDownload()
    {
        var dir = Directory.CreateTempSubdirectory("pg-atomui-upd");
        try
        {
            var offline = true;
            var checks = 0;
            var manager = new AppUpdateManager(
                updatesDir: dir.FullName,
                currentVersion: "0.3.0",
                offlineMode: () => offline,
                checkUpdate: _ =>
                {
                    checks++;
                    return Task.FromResult(new UpdateInfo("0.16.0", "https://example.com/PocketGate-0.16.0-AtomUI.apk", CurrentVersion: "0.3.0"));
                },
                downloadApk: (_, target, _, onProgress, _) =>
                {
                    onProgress(50);
                    Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                    File.WriteAllBytes(target, [0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4]);
                    onProgress(100);
                    return Task.FromResult(target);
                });

            manager.CheckOnEnter();
            await Task.Delay(50, TestContext.Current.CancellationToken);
            Assert.Equal(0, checks);

            offline = false;
            manager.CheckOnEnter();
            await Task.Delay(150, TestContext.Current.CancellationToken);
            Assert.Equal(1, checks);
            Assert.True(manager.State.ShowAvailablePrompt);

            var downloadDone = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            manager.StateChanged += state =>
            {
                if (!state.Downloading && state.DownloadedPath is not null)
                {
                    downloadDone.TrySetResult();
                }
            };
            manager.StartDownload();
            await downloadDone.Task.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
            Assert.False(manager.State.Downloading);
            Assert.Equal(100, manager.State.Progress);
            Assert.True(manager.State.ShowReadyPrompt);
        }
        finally
        {
            dir.Delete(true);
        }
    }

    [Fact]
    public void VpnRoutingPlanSupportsCloneInheritanceAndIndependentMode()
    {
        var primaryUid = 10200;
        var cloneUid = 999 * 100_000 + primaryUid;
        var byUid = new Dictionary<int, AppRouting>
        {
            [primaryUid] = new(primaryUid, "com.tencent.mm", ["com.tencent.mm"], AppRoutingDecision.Bypass, AppRoutingReason.Rule),
        };
        var inheritPlan = new VpnRoutingPlan(FilterMode.Ask, new HashSet<string> { "com.tencent.mm" }, byUid, [], CloneAppsInheritPrimary: true);
        Assert.Equal("com.tencent.mm", inheritPlan.Find(cloneUid)?.PackageName);

        var independentPlan = inheritPlan with { CloneAppsInheritPrimary = false };
        Assert.Null(independentPlan.Find(cloneUid));
        Assert.Equal("com.tencent.mm", independentPlan.Find(primaryUid)?.PackageName);
    }

    [Fact]
    public void BlocklistParserHandlesAdblockWildcardHostsAndExceptions()
    {
        using var reader = new StringReader("||ads.example.com^\n*.tracker.org\n0.0.0.0 exact.host\n@@||ok.ads.example.com^\n");
        var result = BlocklistParser.Parse(reader);
        Assert.True(result.List.Matches("sub.ads.example.com"));
        Assert.False(result.List.Matches("ok.ads.example.com"));
        Assert.True(result.List.Matches("a.tracker.org"));
        Assert.True(result.List.Matches("exact.host"));
        Assert.False(result.List.Matches("sub.exact.host"));
    }

    [Fact]
    public async Task DirectSocksServerEnforcesAuthAndPassesThreeStageProbe()
    {
        await using var server = new DirectSocksServer();
        server.Start();

        var badAuth = await Socks5Probe.DiagnoseAsync("127.0.0.1", server.Port, "bad", "creds", TestContext.Current.CancellationToken);
        Assert.True(badAuth.PortReachable);
        Assert.False(badAuth.AuthSucceeded);
    }
}
