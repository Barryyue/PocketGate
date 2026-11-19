package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.update.UpdateChecker
import com.networkcontrol.android.kotlin.core.update.UpdateSource
import com.networkcontrol.android.kotlin.core.update.compareVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesNumericVersionSegments() {
        assertTrue(compareVersions("0.4.0", "0.3.0") > 0)
        assertEquals(0, compareVersions("0.4.0", "0.4"))
        assertTrue(compareVersions("0.4.1", "0.4.0") > 0)
        assertTrue(compareVersions("1.0.0", "0.99.99") > 0)
    }

    @Test
    fun parsesGitHubReleaseAndPrefersApkAsset() {
        val info = UpdateChecker.parse(
            UpdateSource.GitHub,
            """
            {
              "tag_name": "v0.10.0",
              "html_url": "https://github.com/o/r/releases/tag/v0.10.0",
              "assets": [
                {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt"},
                {"name": "app.apk", "browser_download_url": "https://example.com/app.apk"}
              ]
            }
            """.trimIndent(),
            "0.9.3",
        )
        assertEquals("0.10.0", info.latestVersion)
        assertEquals("https://example.com/app.apk", info.downloadUrl)
        assertTrue(info.hasUpdate)
    }

    @Test
    fun stripsKtVTagPrefixAndPrefersArm64ApkOverEmulatorApk() {
        val info = UpdateChecker.parse(
            UpdateSource.GitHub,
            """
            {
              "tag_name": "kt-v0.13.4-m4p20",
              "html_url": "https://github.com/o/r/releases/tag/kt-v0.13.4-m4p20",
              "assets": [
                {"name": "NetworkControl-KT-0.13.4-m4p20-x86_64-emulator.apk", "browser_download_url": "https://example.com/NetworkControl-KT-0.13.4-m4p20-x86_64-emulator.apk"},
                {"name": "NetworkControl-KT-0.13.4-m4p20.apk", "browser_download_url": "https://example.com/NetworkControl-KT-0.13.4-m4p20.apk"}
              ]
            }
            """.trimIndent(),
            "0.13.3-m4p20",
        )
        assertEquals("0.13.4-m4p20", info.latestVersion)
        assertEquals("https://example.com/NetworkControl-KT-0.13.4-m4p20.apk", info.downloadUrl)
        assertTrue(info.hasUpdate)
    }

    @Test
    fun parsesGitLabReleaseLinks() {
        val info = UpdateChecker.parse(
            UpdateSource.GitLab,
            """
            {
              "tag_name": "v0.11.0",
              "_links": {"self": "https://gitlab.com/g/p/-/releases/v0.11.0"},
              "assets": {"links": [{"name": "app.apk", "url": "https://gitlab.com/app.apk"}]}
            }
            """.trimIndent(),
            "0.10.0",
        )
        assertEquals("0.11.0", info.latestVersion)
        assertEquals("https://gitlab.com/app.apk", info.downloadUrl)
    }

    @Test
    fun parsesGiteeRelease() {
        val info = UpdateChecker.parse(
            UpdateSource.Gitee,
            """
            {
              "tag_name": "v0.12.0",
              "html_url": "https://gitee.com/o/r/releases/v0.12.0",
              "assets": [{"browser_download_url": "https://gitee.com/o/r/app.apk"}]
            }
            """.trimIndent(),
            "0.11.0",
        )
        assertEquals("0.12.0", info.latestVersion)
        assertEquals("https://gitee.com/o/r/app.apk", info.downloadUrl)
    }

    @Test
    fun parsesCustomJson() {
        val info = UpdateChecker.parse(
            UpdateSource.Custom,
            """
            {
              "version": "0.13.0",
              "url": "https://example.com/releases/0.13.0",
              "apk": "https://example.com/NetworkControl-0.13.0.apk"
            }
            """.trimIndent(),
            "0.12.0",
        )
        assertEquals("0.13.0", info.latestVersion)
        assertEquals("https://example.com/NetworkControl-0.13.0.apk", info.downloadUrl)
    }

    @Test
    fun buildsProviderEndpoints() {
        assertEquals(
            "https://api.github.com/repos/o/r/releases/latest",
            UpdateChecker.endpoint(UpdateSource.GitHub, "o/r", ""),
        )
        assertEquals(
            "https://gitlab.com/api/v4/projects/g%2Fp/releases/permalink/latest",
            UpdateChecker.endpoint(UpdateSource.GitLab, "g/p", ""),
        )
        assertEquals(
            "https://gitee.com/api/v5/repos/o/r/releases/latest",
            UpdateChecker.endpoint(UpdateSource.Gitee, "o/r", ""),
        )
        assertEquals(
            "https://example.com/update.json",
            UpdateChecker.endpoint(UpdateSource.Custom, "", "https://example.com/update.json"),
        )
        assertNull(UpdateChecker.endpoint(UpdateSource.GitHub, "missing-slash", ""))
        assertNull(UpdateChecker.endpoint(UpdateSource.Custom, "", "http://insecure"))
    }

    @Test
    fun fallsBackToGiteeWhenGitHubApiFails() {
        var giteeCalled = false
        val info = UpdateChecker.resolveWithFallback(
            fetchGitHub = {
                com.networkcontrol.android.kotlin.core.update.UpdateInfo(
                    latestVersion = null,
                    downloadUrl = null,
                    error = "connect timed out",
                    currentVersion = "0.13.3",
                )
            },
            fetchGitee = {
                giteeCalled = true
                com.networkcontrol.android.kotlin.core.update.UpdateInfo(
                    latestVersion = "0.14.0",
                    downloadUrl = "https://gitee.com/yuexiaobai/networkcontrol/releases/download/v0.14.0/PocketGate-0.14.0.apk",
                    currentVersion = "0.13.3",
                )
            },
            isDownloadReachable = { true },
        )
        assertTrue(giteeCalled)
        assertTrue(info.hasUpdate)
        assertEquals("0.14.0", info.latestVersion)
        assertEquals(
            "https://gitee.com/yuexiaobai/networkcontrol/releases/download/v0.14.0/PocketGate-0.14.0.apk",
            info.downloadUrl,
        )
    }

    @Test
    fun switchesToGiteeDownloadWhenGitHubDownloadIsUnreachable() {
        val info = UpdateChecker.resolveWithFallback(
            fetchGitHub = {
                com.networkcontrol.android.kotlin.core.update.UpdateInfo(
                    latestVersion = "0.14.0",
                    downloadUrl = "https://github.com/Barryyue/network-control/releases/download/v0.14.0/PocketGate-0.14.0.apk",
                    currentVersion = "0.13.3",
                )
            },
            fetchGitee = {
                com.networkcontrol.android.kotlin.core.update.UpdateInfo(
                    latestVersion = "0.14.0",
                    downloadUrl = "https://gitee.com/yuexiaobai/networkcontrol/releases/download/v0.14.0/PocketGate-0.14.0.apk",
                    currentVersion = "0.13.3",
                )
            },
            isDownloadReachable = { url -> !url.contains("github.com") },
        )
        assertTrue(info.hasUpdate)
        assertEquals(
            "https://gitee.com/yuexiaobai/networkcontrol/releases/download/v0.14.0/PocketGate-0.14.0.apk",
            info.downloadUrl,
        )
    }

    @Test
    fun downloadsApkInAppAndFallsBackToMirrorOnFailure() {
        val fakeApkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5, 6, 7, 8)
        java.net.ServerSocket(0).use { server ->
            val port = server.localPort
            val thread = Thread {
                // 第一次請求（模擬主源失敗 HTTP 502）
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                    socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
                // 第二次請求（模擬備用源回傳有效 APK）
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                    val out = socket.getOutputStream()
                    out.write(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: ${fakeApkBytes.size}\r\n\r\n"
                            .toByteArray(),
                    )
                    out.write(fakeApkBytes)
                    out.flush()
                }
            }.apply { isDaemon = true; start() }

            val target = java.io.File.createTempFile("pocketgate-test-", ".apk")
            try {
                val progresses = mutableListOf<Int>()
                val result = kotlinx.coroutines.runBlocking {
                    UpdateChecker.downloadApk(
                        url = "http://127.0.0.1:$port/primary.apk",
                        targetFile = target,
                        fallbackUrl = "http://127.0.0.1:$port/fallback.apk",
                        currentVersion = "0.13.4",
                        onProgress = { progresses.add(it) },
                    )
                }
                thread.join(3_000)
                assertTrue("download should succeed via fallback: ${result.exceptionOrNull()}", result.isSuccess)
                assertEquals(fakeApkBytes.size.toLong(), target.length())
                assertTrue(progresses.contains(100))
            } finally {
                target.delete()
            }
        }
    }

    @Test
    fun appUpdateManagerChecksOnEnterRespectsOfflineAndTracksDownloadToReadyPrompt() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("pg-upd").toFile()
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined,
        )
        try {
            var offline = true
            var checkCalls = 0
            val manager = com.networkcontrol.android.kotlin.core.update.AppUpdateManager(
                scope = scope,
                updatesDir = dir,
                currentVersion = "0.15.6",
                offlineMode = { offline },
                checkUpdate = {
                    checkCalls++
                    com.networkcontrol.android.kotlin.core.update.UpdateInfo(
                        latestVersion = "0.15.7",
                        downloadUrl = "https://example.com/PocketGate-0.15.7.apk",
                        currentVersion = "0.15.6",
                    )
                },
                downloadApk = { _, targetFile, _, onProgress ->
                    onProgress(40)
                    targetFile.parentFile?.mkdirs()
                    targetFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4))
                    onProgress(100)
                    Result.success(targetFile)
                },
                nowMs = { 1_000_000L },
            )

            // 純離線模式下進入應用不應發起自動檢查
            manager.checkOnEnter()
            assertEquals(0, checkCalls)

            // 關閉離線模式後進入應用自動檢查並彈出新版本提示
            offline = false
            manager.checkOnEnter()
            assertEquals(1, checkCalls)
            assertTrue(manager.state.value.showAvailablePrompt)
            assertEquals("0.15.7", manager.state.value.info?.latestVersion)

            // 開始下載：進度寫入檔案，完成後清除「發現新版本」並彈出「已就緒提醒安裝」
            manager.startDownload()
            val state = manager.state.value
            assertTrue(!state.downloading)
            assertEquals(100, state.progress)
            assertTrue(state.downloadedFile?.exists() == true)
            assertTrue(state.showReadyPrompt)
            assertTrue(!state.showAvailablePrompt)

            manager.dismissReadyPrompt()
            assertTrue(!manager.state.value.showReadyPrompt)
            assertTrue(manager.state.value.downloadedFile != null)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parsesReleaseNotesAndEncryptsWebDavBackupRoundTrip() = kotlinx.coroutines.runBlocking {
        val parsed = UpdateChecker.parse(
            UpdateSource.GitHub,
            """
            {
              "tag_name": "v0.16.0",
              "body": "- WebDAV encrypted sync\n- Independent clone app rules",
              "assets": [
                {"name": "PocketGate-0.16.0.apk", "browser_download_url": "https://example.com/PocketGate-0.16.0.apk"}
              ]
            }
            """.trimIndent(),
            "0.15.7",
        )
        assertTrue(parsed.releaseNotes?.contains("WebDAV encrypted sync") == true)

        val sampleJson = """{"format":"netcontrol-rules","version":1,"rules":[{"id":"r1"}]}"""
        val encrypted = com.networkcontrol.android.kotlin.data.backup.WebDavBackupClient.encrypt(sampleJson, "my-secret-key")
        assertEquals(sampleJson, com.networkcontrol.android.kotlin.data.backup.WebDavBackupClient.decrypt(encrypted, "my-secret-key"))
        val wrongKey = runCatching {
            com.networkcontrol.android.kotlin.data.backup.WebDavBackupClient.decrypt(encrypted, "wrong-key")
        }
        assertTrue(wrongKey.isFailure)
    }
}
