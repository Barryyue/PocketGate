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
}
