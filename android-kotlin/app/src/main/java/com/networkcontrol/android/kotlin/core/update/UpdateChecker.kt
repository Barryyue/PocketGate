package com.networkcontrol.android.kotlin.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    val releaseNotes: String? = null,
) {
    val hasUpdate: Boolean
        get() = latestVersion != null && compareVersions(latestVersion, currentVersion) > 0
}

/**
 * 公開網路請求：使用 Android 的預設 ProxySelector（跟隨系統代理）。
 * 不要在這裡關閉代理，否則只能透過代理上網的裝置會失敗。
 *
 * 預設自動檢查策略（[checkAuto]）：
 * 1. 優先查詢 GitHub Releases（[DEFAULT_GITHUB_REPO]；若 GitHub API 遇到 403 速率限制，自動改用 `releases/latest` 302 重定向解析版本與 APK 位址）；
 * 2. 若發現新版本，自動探測 GitHub 附件實際下載域名（`release-assets.githubusercontent.com`）是否可連通；
 * 3. 若 GitHub 連不通或下載域名被阻斷，自動依序回退至 Gitee Releases（[DEFAULT_GITEE_REPO]）與 GitHub 國內加速鏡像（[GITHUB_MIRROR_PREFIX]），全程在應用內完成下載。
 */
object UpdateChecker {
    const val DEFAULT_GITHUB_REPO = "Barryyue/PocketGate"
    const val LEGACY_GITHUB_REPO = "Barryyue/network-control"
    const val DEFAULT_GITEE_REPO = "yuexiaobai/PocketGate"
    const val LEGACY_GITEE_REPO = "yuexiaobai/networkcontrol"
    const val GITHUB_MIRROR_PREFIX = "https://ghfast.top/"

    private const val GITHUB_LATEST = "https://api.github.com/repos/%s/releases?per_page=20"
    private const val GITHUB_WEB_LATEST = "https://github.com/%s/releases/latest"
    private const val GITLAB_LATEST =
        "https://gitlab.com/api/v4/projects/%s/releases/permalink/latest"
    private const val GITEE_LATEST = "https://gitee.com/api/v5/repos/%s/releases?page=1&per_page=20"

    /**
     * 自動檢查更新：先走 GitHub，連不通或下載地址不可達時自動回退到 Gitee 或國內加速鏡像。
     */
    suspend fun checkAuto(
        currentVersion: String,
        githubRepo: String = DEFAULT_GITHUB_REPO,
        giteeRepo: String = DEFAULT_GITEE_REPO,
    ): UpdateInfo = withContext(Dispatchers.IO) {
        resolveWithFallback(
            fetchGitHub = {
                val primary = checkGitHubWithWebFallback(currentVersion, githubRepo)
                if (primary.error != null && githubRepo == DEFAULT_GITHUB_REPO) {
                    val legacy = checkGitHubWithWebFallback(currentVersion, LEGACY_GITHUB_REPO)
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

    private suspend fun checkGitHubWithWebFallback(currentVersion: String, repo: String): UpdateInfo {
        val apiResult = check(currentVersion, UpdateSource.GitHub, repo)
        if (apiResult.error == null && apiResult.latestVersion != null) return apiResult
        val webResult = checkGitHubRedirect(currentVersion, repo)
        return if (webResult.error == null && webResult.latestVersion != null) webResult else apiResult
    }

    /**
     * 當 `api.github.com` 遇到未登入 IP 速率限制（HTTP 403）時，
     * 透過 `https://github.com/{owner}/{repo}/releases/latest` 的 302 Location 標頭解析最新 Tag 與 APK 位址。
     */
    internal fun checkGitHubRedirect(currentVersion: String, repo: String): UpdateInfo = runCatching {
        val url = GITHUB_WEB_LATEST.format(repo.trim())
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "PocketGate/$currentVersion")
        }
        try {
            val location = connection.getHeaderField("Location").orEmpty()
            val marker = "/releases/tag/"
            val idx = location.indexOf(marker)
            if (idx < 0) {
                return@runCatching UpdateInfo(null, null, "HTTP ${connection.responseCode}", currentVersion)
            }
            val rawTag = location.substring(idx + marker.length).substringBefore('?').substringBefore('/').trim()
            if (rawTag.startsWith("atomui-v", ignoreCase = true)) {
                return@runCatching UpdateInfo(null, null, null, currentVersion)
            }
            val version = rawTag.removePrefix("kt-v").removePrefix("v")
            if (version.isBlank()) {
                return@runCatching UpdateInfo(null, null, "empty tag", currentVersion)
            }
            val apkUrl = "https://github.com/${repo.trim()}/releases/download/$rawTag/PocketGate-$version.apk"
            UpdateInfo(
                latestVersion = version,
                downloadUrl = apkUrl,
                currentVersion = currentVersion,
            )
        } finally {
            connection.disconnect()
        }
    }.getOrElse { UpdateInfo(null, null, it.message ?: it.javaClass.simpleName, currentVersion) }

    internal inline fun resolveWithFallback(
        fetchGitHub: () -> UpdateInfo,
        fetchGitee: () -> UpdateInfo,
        isDownloadReachable: (String) -> Boolean,
    ): UpdateInfo {
        val github = fetchGitHub()
        if (github.error == null && github.latestVersion != null) {
            if (!github.hasUpdate) return github
            val ghUrl = github.downloadUrl
            val mirrorUrl = ghUrl?.takeIf { it.startsWith("https://github.com/", ignoreCase = true) }
                ?.let { "$GITHUB_MIRROR_PREFIX$it" }
            if (!ghUrl.isNullOrBlank() && isDownloadReachable(ghUrl)) {
                return github.copy(fallbackDownloadUrl = mirrorUrl)
            }
            val gitee = fetchGitee()
            if (gitee.error == null && !gitee.downloadUrl.isNullOrBlank()) {
                return if (gitee.hasUpdate) {
                    gitee.copy(fallbackDownloadUrl = mirrorUrl ?: ghUrl)
                } else {
                    github.copy(downloadUrl = gitee.downloadUrl, fallbackDownloadUrl = mirrorUrl ?: ghUrl)
                }
            }
            if (mirrorUrl != null) {
                return github.copy(downloadUrl = mirrorUrl, fallbackDownloadUrl = ghUrl)
            }
            return github
        }
        val gitee = fetchGitee()
        return if (gitee.error == null || github.latestVersion == null) gitee else github
    }

    /**
     * 在應用內直接下載更新 APK 到 [targetFile]，並透過 [onProgress] 回報 0..100 的進度百分比。
     * 若主連結 [url] 下載失敗，會依序嘗試 [fallbackUrl]、Gitee Releases 鏡像，以及 GitHub 加速鏡像（[GITHUB_MIRROR_PREFIX]）。
     */
    suspend fun downloadApk(
        url: String,
        targetFile: File,
        fallbackUrl: String? = null,
        currentVersion: String = "0.0.0",
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        val candidates = linkedSetOf<String>()
        candidates.add(url)
        if (!fallbackUrl.isNullOrBlank()) {
            candidates.add(fallbackUrl)
        }
        val rawGitHubUrl = when {
            url.startsWith(GITHUB_MIRROR_PREFIX, ignoreCase = true) ->
                url.removePrefix(GITHUB_MIRROR_PREFIX)
            url.startsWith("https://github.com/", ignoreCase = true) -> url
            fallbackUrl?.startsWith("https://github.com/", ignoreCase = true) == true -> fallbackUrl
            else -> null
        }
        if (rawGitHubUrl != null) {
            candidates.add("$GITHUB_MIRROR_PREFIX$rawGitHubUrl")
            candidates.add(rawGitHubUrl)
        }

        var lastResult: Result<File> = Result.failure(IOException("No download URL"))
        for (candidate in candidates) {
            onProgress(0)
            val attempt = runCatching {
                downloadFromUrl(candidate, targetFile, currentVersion, onProgress)
            }
            if (attempt.isSuccess) return@withContext attempt
            lastResult = attempt
        }

        // 若以上候選皆失敗且尚未查過 Gitee，最後查詢一次 Gitee Releases
        if (rawGitHubUrl != null) {
            val gitee = check(currentVersion, UpdateSource.Gitee, DEFAULT_GITEE_REPO).let {
                if (it.error != null) check(currentVersion, UpdateSource.Gitee, LEGACY_GITEE_REPO) else it
            }
            val giteeUrl = gitee.downloadUrl?.takeIf { it.endsWith(".apk", ignoreCase = true) && it !in candidates }
            if (giteeUrl != null) {
                onProgress(0)
                val giteeAttempt = runCatching {
                    downloadFromUrl(giteeUrl, targetFile, currentVersion, onProgress)
                }
                if (giteeAttempt.isSuccess) return@withContext giteeAttempt
                lastResult = giteeAttempt
            }
        }
        lastResult
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

    /**
     * 追蹤最多 3 層重定向（例如 `github.com` 302 到 `release-assets.githubusercontent.com`），
     * 確保實際承載 APK 的檔案伺服器也能連通，而不是只看到第一跳 302 就誤判可達。
     */
    private fun isUrlReachable(url: String, currentVersion: String): Boolean = runCatching {
        var currentUrl = url
        var redirects = 0
        while (redirects < 4) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_500
                readTimeout = 3_500
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", "PocketGate/$currentVersion")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return@runCatching false
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                    continue
                }
                return@runCatching code in 200..299
            } finally {
                connection.disconnect()
            }
        }
        false
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
        val trimmed = body.trimStart()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optBoolean("draft", false) || item.optBoolean("prerelease", false)) continue
                val rawTag = item.optString("tag_name").trim()
                if (rawTag.startsWith("atomui-v", ignoreCase = true)) continue
                val parsed = parseObject(source, item, currentVersion)
                if (parsed.latestVersion != null && !isAtomUiOnlyRelease(item, parsed.downloadUrl)) {
                    return parsed
                }
            }
            return UpdateInfo(null, null, null, currentVersion)
        }
        return parseObject(source, JSONObject(trimmed), currentVersion)
    }

    private fun isAtomUiOnlyRelease(json: JSONObject, chosenUrl: String?): Boolean {
        if (chosenUrl != null && chosenUrl.endsWith(".apk", ignoreCase = true) && !chosenUrl.contains("AtomUI", ignoreCase = true)) {
            return false
        }
        val assets = json.optJSONArray("assets") ?: return false
        var hasApk = false
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val url = asset.optString("browser_download_url")
                .ifBlank { asset.optString("url") }
                .ifBlank { asset.optString("direct_asset_url") }
            if (url.endsWith(".apk", ignoreCase = true)) {
                hasApk = true
                if (!url.contains("AtomUI", ignoreCase = true)) return false
            }
        }
        return hasApk
    }

    private fun parseObject(source: UpdateSource, json: JSONObject, currentVersion: String): UpdateInfo {
        val tag = when (source) {
            UpdateSource.Custom -> json.optString("version").ifBlank { json.optString("tag_name") }
            else -> json.optString("tag_name")
        }.trim().removePrefix("kt-v").removePrefix("v")
        val releaseUrl = json.optString("html_url")
            .ifBlank { json.optString("url") }
            .ifBlank { json.optJSONObject("_links")?.optString("self").orEmpty() }
        val apk = findApk(source, json)
        val notes = when (source) {
            UpdateSource.GitLab -> json.optString("description")
            UpdateSource.Custom -> json.optString("notes").ifBlank { json.optString("changelog") }
            else -> json.optString("body")
        }.trim().takeIf { it.isNotBlank() }
        return UpdateInfo(
            latestVersion = tag.takeIf { it.isNotBlank() },
            downloadUrl = apk ?: releaseUrl.takeIf { it.isNotBlank() },
            currentVersion = currentVersion,
            releaseNotes = notes,
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
                if (url.endsWith(".apk", ignoreCase = true) && !url.contains("AtomUI", ignoreCase = true)) {
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
