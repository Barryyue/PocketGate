package com.networkcontrol.android.automation.network

import com.networkcontrol.android.automation.model.ParsedSubscription
import com.networkcontrol.android.automation.model.SubscriptionRecord
import com.networkcontrol.android.automation.parser.GkdSubscriptionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 訂閱遠端下載與版本檢查器。 */
class SubscriptionFetcher(
    private val openConnection: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    },
) {
    sealed interface FetchResult {
        data class Success(val parsed: ParsedSubscription, val rawText: String) : FetchResult
        data class UpToDate(val version: Int) : FetchResult
        data class Error(val message: String) : FetchResult
    }

    /** 從 URL 下載並解析訂閱；若提供 `existing`，會先檢查 `checkUpdateUrl` 避免重複下載大檔。 */
    suspend fun fetch(
        url: String,
        existing: SubscriptionRecord? = null,
    ): FetchResult = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        val isLocalHttp = lower.startsWith("http://127.0.0.1") || lower.startsWith("http://localhost")
        if (!lower.startsWith("https://") && !isLocalHttp) {
            return@withContext FetchResult.Error("Only HTTPS URLs are allowed")
        }

        // 若有 checkUpdateUrl，先做輕量版本檢查（支援 ./gkd.version.json5 相對路徑）
        val checkUrl = existing?.checkUpdateUrl?.let { raw ->
            runCatching { URL(URL(trimmed), raw).toString() }.getOrNull()
        }
        if (!checkUrl.isNullOrBlank()) {
            val remoteVersion = runCatching { downloadText(checkUrl) }
                .mapCatching { GkdSubscriptionParser.parseVersionInfo(it) }
                .getOrNull()
            if (remoteVersion != null && remoteVersion <= existing.version) {
                return@withContext FetchResult.UpToDate(existing.version)
            }
        }

        runCatching {
            val text = downloadText(trimmed)
            val parsed = GkdSubscriptionParser.parse(text, validateSelectors = true)
            if (existing != null && parsed.subscription.version <= existing.version) {
                FetchResult.UpToDate(existing.version)
            } else {
                FetchResult.Success(parsed = parsed, rawText = text)
            }
        }.getOrElse { e ->
            FetchResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun downloadText(url: String): String {
        val conn = openConnection(url)
        conn.connectTimeout = 15_000
        conn.readTimeout = 25_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "PocketGate-Automation/1.0")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
