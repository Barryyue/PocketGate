package com.networkcontrol.android.kotlin.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 更新檢查的來源。 */
enum class UpdateSource {
    GitHub,
    GitLab,
    Gitee,

    /** 任意 HTTPS URL，回傳包含 version / url / apk 的 JSON。 */
    Custom,
}

data class UpdateInfo(
    val latestVersion: String?,
    val downloadUrl: String?,
    val error: String? = null,
    val currentVersion: String = "0.0.0",
) {
    val hasUpdate: Boolean
        get() = latestVersion != null && compareVersions(latestVersion, currentVersion) > 0
}

/**
 * 公開網路請求：使用 Android 的預設 ProxySelector（跟隨系統代理）。
 * 不要在這裡關閉代理，否則只能透過代理上網的裝置會失敗。
 *
 * 預設自動檢查策略（[checkAuto]）：
 * 1. 優先查詢 GitHub Releases（[DEFAULT_GITHUB_REPO]），若有新版本則同時探測 GitHub 下載連結是否可連通；
 * 2. 若 GitHub API 連不通，或 GitHub 的 APK 下載連結無法連線，自動切換至 Gitee Releases（[DEFAULT_GITEE_REPO]），無須使用者手動選擇。
 */
object UpdateChecker {
    const val DEFAULT_GITHUB_REPO = "Barryyue/PocketGate"
    const val LEGACY_GITHUB_REPO = "Barryyue/network-control"
    const val DEFAULT_GITEE_REPO = "yuexiaobai/PocketGate"
    const val LEGACY_GITEE_REPO = "yuexiaobai/networkcontrol"

    private const val GITHUB_LATEST = "https://api.github.com/repos/%s/releases/latest"
    private const val GITLAB_LATEST =
        "https://gitlab.com/api/v4/projects/%s/releases/permalink/latest"
    private const val GITEE_LATEST = "https://gitee.com/api/v5/repos/%s/releases/latest"

    /**
     * 自動檢查更新：先走 GitHub，連不通或下載地址不可達時自動回退到 Gitee（同時相容改名前後的儲存庫名稱）。
     */
    suspend fun checkAuto(
        currentVersion: String,
        githubRepo: String = DEFAULT_GITHUB_REPO,
        giteeRepo: String = DEFAULT_GITEE_REPO,
    ): UpdateInfo = withContext(Dispatchers.IO) {
        resolveWithFallback(
            fetchGitHub = {
                val primary = check(currentVersion, UpdateSource.GitHub, githubRepo)
                if (primary.error != null && githubRepo == DEFAULT_GITHUB_REPO) {
                    val legacy = check(currentVersion, UpdateSource.GitHub, LEGACY_GITHUB_REPO)
                    if (legacy.error == null) legacy else primary
                } else {
                    primary
                }
            },
            fetchGitee = {
                val primary = check(currentVersion, UpdateSource.Gitee, giteeRepo)
                if (primary.error != null && giteeRepo == DEFAULT_GITEE_REPO) {
                    val legacy = check(currentVersion, UpdateSource.Gitee, LEGACY_GITEE_REPO)
                    if (legacy.error == null) legacy else primary
                } else {
                    primary
                }
            },
            isDownloadReachable = { url -> isUrlReachable(url, currentVersion) },
        )
    }

    internal inline fun resolveWithFallback(
        fetchGitHub: () -> UpdateInfo,
        fetchGitee: () -> UpdateInfo,
        isDownloadReachable: (String) -> Boolean,
    ): UpdateInfo {
        val github = fetchGitHub()
        if (github.error == null && github.latestVersion != null) {
            if (!github.hasUpdate) return github
            val ghUrl = github.downloadUrl
            if (!ghUrl.isNullOrBlank() && isDownloadReachable(ghUrl)) {
                return github
            }
            val gitee = fetchGitee()
            if (gitee.error == null && !gitee.downloadUrl.isNullOrBlank()) {
                return if (gitee.hasUpdate) {
                    gitee
                } else {
                    github.copy(downloadUrl = gitee.downloadUrl)
                }
            }
            return github
        }
        val gitee = fetchGitee()
        return if (gitee.error == null || github.latestVersion == null) gitee else github
    }

    private fun isUrlReachable(url: String, currentVersion: String): Boolean = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("User-Agent", "PocketGate/$currentVersion")
        }
        try {
            connection.responseCode in 200..399
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    suspend fun check(
        currentVersion: String,
        source: UpdateSource = UpdateSource.GitHub,
        repo: String = DEFAULT_GITHUB_REPO,
        customUrl: String = "",
    ): UpdateInfo = withContext(Dispatchers.IO) {
        val endpoint = endpoint(source, repo, customUrl)
        if (endpoint.isNullOrBlank()) {
            return@withContext UpdateInfo(
                null,
                null,
                "empty update URL",
                currentVersion,
            )
        }
        runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "PocketGate/$currentVersion")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    return@runCatching UpdateInfo(null, null, "HTTP ${connection.responseCode}", currentVersion)
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parse(source, body, currentVersion)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { UpdateInfo(null, null, it.message ?: it.javaClass.simpleName, currentVersion) }
    }

    internal fun endpoint(source: UpdateSource, repo: String, customUrl: String): String? {
        val trimmed = repo.trim()
        return when (source) {
            UpdateSource.GitHub -> {
                if (!Regex("^[^/\\s]+/[^/\\s]+$").matches(trimmed)) return null
                GITHUB_LATEST.format(trimmed)
            }
            UpdateSource.GitLab -> {
                if (trimmed.isBlank()) return null
                GITLAB_LATEST.format(URLEncoder.encode(trimmed, "UTF-8"))
            }
            UpdateSource.Gitee -> {
                if (!Regex("^[^/\\s]+/[^/\\s]+$").matches(trimmed)) return null
                GITEE_LATEST.format(trimmed)
            }
            UpdateSource.Custom -> customUrl.trim().takeIf { it.startsWith("https://") }
        }
    }

    internal fun parse(source: UpdateSource, body: String, currentVersion: String): UpdateInfo {
        val json = JSONObject(body)
        val tag = when (source) {
            UpdateSource.Custom -> json.optString("version").ifBlank { json.optString("tag_name") }
            else -> json.optString("tag_name")
        }.trim().removePrefix("kt-v").removePrefix("v")
        val releaseUrl = json.optString("html_url")
            .ifBlank { json.optString("url") }
            .ifBlank { json.optJSONObject("_links")?.optString("self").orEmpty() }
        val apk = findApk(source, json)
        return UpdateInfo(
            latestVersion = tag.takeIf { it.isNotBlank() },
            downloadUrl = apk ?: releaseUrl.takeIf { it.isNotBlank() },
            currentVersion = currentVersion,
        )
    }

    private fun findApk(source: UpdateSource, json: JSONObject): String? {
        if (source == UpdateSource.Custom) {
            val direct = json.optString("apk")
                .ifBlank { json.optString("download_url") }
                .ifBlank { json.optString("url") }
            if (direct.endsWith(".apk", ignoreCase = true)) return direct
        }
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            var fallbackApk: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val url = asset.optString("browser_download_url")
                    .ifBlank { asset.optString("url") }
                    .ifBlank { asset.optString("direct_asset_url") }
                if (url.endsWith(".apk", ignoreCase = true)) {
                    if (!url.contains("x86_64", ignoreCase = true) && !url.contains("emulator", ignoreCase = true)) {
                        return url
                    }
                    if (fallbackApk == null) fallbackApk = url
                }
            }
            if (fallbackApk != null) return fallbackApk
        }
        val links = json.optJSONObject("assets")?.optJSONArray("links")
        if (links != null) {
            for (i in 0 until links.length()) {
                val link = links.optJSONObject(i) ?: continue
                val url = link.optString("direct_asset_url")
                    .ifBlank { link.optString("url") }
                if (url.endsWith(".apk", ignoreCase = true)) return url
            }
        }
        return null
    }
}

fun compareVersions(a: String, b: String): Int {
    val left = a.split('.', '-').mapNotNull { it.toIntOrNull() }
    val right = b.split('.', '-').mapNotNull { it.toIntOrNull() }
    val size = maxOf(left.size, right.size)
    for (index in 0 until size) {
        val l = left.getOrElse(index) { 0 }
        val r = right.getOrElse(index) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}
