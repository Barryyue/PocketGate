package com.networkcontrol.android.kotlin.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
 */
object UpdateChecker {
    private const val URL_LATEST = "https://api.github.com/repos/Barryyue/network-control/releases/latest"

    suspend fun check(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(URL_LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "NetworkControl-KT/$currentVersion")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    return@runCatching UpdateInfo(null, null, "HTTP ${connection.responseCode}", currentVersion)
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").trim().removePrefix("v")
                val url = json.optString("html_url").takeIf { it.isNotBlank() }
                UpdateInfo(tag.takeIf { it.isNotBlank() }, url, currentVersion = currentVersion)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { UpdateInfo(null, null, it.message ?: it.javaClass.simpleName, currentVersion) }
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
