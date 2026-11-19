package com.networkcontrol.android.kotlin.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    val fallbackDownloadUrl: String? = null,
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
 * 3. 支援在應用內直接下載 APK（[downloadApk]）：若主下載連結中途失敗，會自動回退至備用下載連結（或即時查詢 Gitee 鏡像下載）。
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
                    gitee.copy(fallbackDownloadUrl = ghUrl)
                } else {
                    github.copy(downloadUrl = gitee.downloadUrl, fallbackDownloadUrl = ghUrl)
                }
            }
            return github
        }
        val gitee = fetchGitee()
        return if (gitee.error == null || github.latestVersion == null) gitee else github
    }

    /**
     * 在應用內直接下載更新 APK 到 [targetFile]，並透過 [onProgress] 回報 0..100 的進度百分比。
     * 若主連結 [url] 下載失敗，會自動嘗試 [fallbackUrl]（或自動查詢 Gitee Releases 鏡像下載）。
     */
    suspend fun downloadApk(
        url: String,
        targetFile: File,
        fallbackUrl: String? = null,
        currentVersion: String = "0.0.0",
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        val primaryResult = runCatching {
            downloadFromUrl(url, targetFile, currentVersion, onProgress)
        }
        if (primaryResult.isSuccess) return@withContext primaryResult

        val resolvedFallback = fallbackUrl?.takeIf { it.isNotBlank() && it != url }
            ?: if (url.contains("github.com", ignoreCase = true)) {
                val gitee = check(currentVersion, UpdateSource.Gitee, DEFAULT_GITEE_REPO).let {
                    if (it.error != null) check(currentVersion, UpdateSource.Gitee, LEGACY_GITEE_REPO) else it
                }
                gitee.downloadUrl?.takeIf { it.endsWith(".apk", ignoreCase = true) && it != url }
            } else {
                null
            }

        if (resolvedFallback != null) {
            onProgress(0)
            runCatching {
                downloadFromUrl(resolvedFallback, targetFile, currentVersion, onProgress)
            }
        } else {
            primaryResult
        }
    }

    private fun downloadFromUrl(
        initialUrl: String,
        targetFile: File,
        currentVersion: String,
        onProgress: (Int) -> Unit,
    ): File {
        targetFile.parentFile?.mkdirs()
        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 6) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                setRequestProperty("User-Agent", "PocketGate/$currentVersion")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location")
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    throw IOException("HTTP $code")
                }
                val totalBytes = connection.contentLengthLong
                var downloadedBytes = 0L
                var lastProgress = -1
                onProgress(0)
                connection.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val percent = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (percent != lastProgress) {
                                    lastProgress = percent
                                    onProgress(percent)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                // 驗證下載到的檔案確實是 ZIP / APK（前 4 位元組為 PK\x03\x04），避免把 HTML 登入/錯誤頁當成 APK。
                if (partFile.length() < 4L || !hasZipMagic(partFile)) {
                    partFile.delete()
                    throw IOException("Downloaded file is not a valid APK")
                }
                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                onProgress(100)
                return targetFile
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun hasZipMagic(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
        }
    }.getOrDefault(false)

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
