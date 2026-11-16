package com.networkcontrol.android.kotlin.engine.blocklist

import com.networkcontrol.android.kotlin.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class BlocklistSourceState(
    val id: String,
    val name: String,
    val urls: List<String>,
    val builtin: Boolean,
    val enabled: Boolean,
    /** 從檔案匯入的名單：沒有地址，不會自動更新。 */
    val imported: Boolean = false,
    val entries: Int = 0,
    val updatedAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val lastError: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
)

data class BlocklistState(
    val enabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val intervalDays: Int = 7,
    /** 第一次開啟時才套用內建名單的預設勾選，之後尊重使用者的選擇。 */
    val initialized: Boolean = false,
    val sources: List<BlocklistSourceState> = emptyList(),
    val updating: Boolean = false,
)

/** 一次更新的結果，供界面顯示。 */
data class BlocklistUpdateSummary(val updated: Int, val unchanged: Int, val failed: Int, val skippedMetered: Boolean = false)

/**
 * 攔截列表的訂閱、下載、編譯與匹配。
 *
 * - 名單編譯成雜湊表存在 [directory]（`<id>.bin`），啟動時直接載入，不必重新解析文字。
 * - 下載使用條件請求（ETag / Last-Modified），沒有變化時伺服器回 304，不重新下載。
 * - 每個來源依序嘗試主地址與鏡像；全部失敗時保留上一份名單，不影響上網。
 * - 熱路徑只讀 [matcher]（不可變快照），更新時整個替換。
 */
class BlocklistManager(
    private val directory: File,
    private val scope: CoroutineScope,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val isUnmetered: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    /** 勾選 / 新增名單後立即在背景下載；測試中關閉以避免與手動更新競爭。 */
    private val autoDownload: Boolean = true,
) {
    private val gate = Mutex()
    private val stateFile = File(directory, "state.json")
    private val loaded = HashMap<String, DomainBlocklist>()

    private val _state = MutableStateFlow(BlocklistState())
    val state: StateFlow<BlocklistState> = _state.asStateFlow()

    private val _matcher = MutableStateFlow(BlocklistMatcher.Empty)

    /** 目前生效的匹配器；關閉攔截列表時為空。 */
    val matcher: StateFlow<BlocklistMatcher> = _matcher.asStateFlow()

    /** 生效的名單改變時呼叫（引擎據此清空連線判決緩存）。 */
    var onChanged: (() -> Unit)? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        gate.withLock {
            directory.mkdirs()
            _state.value = mergeBuiltin(load())
            rebuildLocked()
        }
    }

    /** 啟動自動更新：開機後稍候檢查一次，之後每小時檢查是否到期。 */
    fun start() {
        scope.launch {
            delay(START_DELAY_MS)
            while (isActive) {
                runCatching { updateDue() }.onFailure { AppLog.w("blocklist", "scheduled update failed: ${it.message}") }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        mutate { state ->
            if (enabled && !state.initialized) {
                state.copy(
                    enabled = true,
                    initialized = true,
                    sources = state.sources.map { source ->
                        if (source.builtin) source.copy(enabled = BlocklistCatalog.find(source.id)?.defaultEnabled == true) else source
                    },
                )
            } else {
                state.copy(enabled = enabled)
            }
        }
        if (enabled && autoDownload) scope.launch { runCatching { updateMissing() } }
    }

    suspend fun setWifiOnly(value: Boolean) = mutate { it.copy(wifiOnly = value) }

    suspend fun setIntervalDays(days: Int) = mutate { it.copy(intervalDays = days.coerceIn(1, 30)) }

    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        mutate { state -> state.copy(sources = state.sources.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
        if (enabled && autoDownload) scope.launch { runCatching { updateMissing() } }
    }

    /** 新增自訂訂閱；只接受 https（本機地址可用 http，方便測試）。回傳錯誤訊息，成功為 null。 */
    suspend fun addCustom(name: String, url: String): String? {
        val trimmed = url.trim()
        val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return "invalid url"
        if (!isAllowedUrl(parsed)) return "https required"
        val id = "custom-" + UUID.randomUUID().toString().take(8)
        mutate { state ->
            state.copy(
                sources = state.sources + BlocklistSourceState(
                    id = id,
                    name = name.trim().ifEmpty { parsed.host },
                    urls = listOf(trimmed),
                    builtin = false,
                    enabled = true,
                ),
            )
        }
        if (autoDownload) scope.launch { runCatching { updateMissing() } }
        return null
    }

    /** 從檔案匯入名單（離線使用）；成功回傳條目數。 */
    suspend fun import(name: String, input: InputStream): Int = withContext(Dispatchers.IO) {
        val result = BlocklistParser.parse(CappedInputStream(input, MAX_DOWNLOAD_BYTES))
        if (result.list.size == 0) throw IOException("no domains found")
        val id = "file-" + UUID.randomUUID().toString().take(8)
        gate.withLock {
            result.list.writeTo(File(directory, "$id.bin"))
            loaded[id] = result.list
            val source = BlocklistSourceState(
                id = id,
                name = name.trim().ifEmpty { result.title ?: id },
                urls = emptyList(),
                builtin = false,
                enabled = true,
                imported = true,
                entries = result.list.size,
                updatedAt = clock(),
            )
            commitLocked(_state.value.copy(sources = _state.value.sources + source))
        }
        result.list.size
    }

    suspend fun remove(id: String) {
        mutate { state -> state.copy(sources = state.sources.filterNot { it.id == id && !it.builtin }) }
        withContext(Dispatchers.IO) { File(directory, "$id.bin").delete() }
    }

    /** 立即更新所有已勾選的名單（忽略到期時間，但仍遵守「僅 Wi-Fi」）。 */
    suspend fun updateNow(): BlocklistUpdateSummary = update(force = true, ignoreMetered = true)

    private suspend fun updateDue() {
        val state = _state.value
        if (!state.enabled) return
        update(force = false)
    }

    /** 使用者剛勾選 / 新增的名單：立即下載，不受「僅 Wi-Fi」限制（這是使用者的直接操作）。 */
    private suspend fun updateMissing() {
        if (!_state.value.enabled) return
        update(force = false, onlyMissing = true, ignoreMetered = true)
    }

    private suspend fun update(
        force: Boolean,
        onlyMissing: Boolean = false,
        ignoreMetered: Boolean = false,
    ): BlocklistUpdateSummary = withContext(Dispatchers.IO) {
        val snapshot = _state.value
        val now = clock()
        val candidates = snapshot.sources.filter { source ->
            source.enabled && !source.imported && source.urls.isNotEmpty() && when {
                onlyMissing -> source.updatedAt == 0L && now - source.lastAttemptAt > RETRY_MISSING_MS
                force -> true
                else -> isDue(source, snapshot.intervalDays, now)
            }
        }
        if (candidates.isEmpty()) return@withContext BlocklistUpdateSummary(0, 0, 0)
        // 「僅 Wi-Fi」只限制自動更新；使用者手動操作時照樣下載。
        if (!ignoreMetered && snapshot.wifiOnly && !isUnmetered()) {
            return@withContext BlocklistUpdateSummary(0, 0, 0, skippedMetered = true)
        }
        setUpdating(true)
        var updated = 0
        var unchanged = 0
        var failed = 0
        try {
            for (source in candidates) {
                when (val outcome = download(source)) {
                    is Outcome.Updated -> {
                        updated++
                        gate.withLock {
                            loaded[source.id] = outcome.list
                            replaceSourceLocked(source.id) {
                                it.copy(
                                    entries = outcome.list.size,
                                    updatedAt = clock(),
                                    lastAttemptAt = clock(),
                                    lastError = null,
                                    etag = outcome.etag,
                                    lastModified = outcome.lastModified,
                                )
                            }
                        }
                    }
                    Outcome.NotModified -> {
                        unchanged++
                        gate.withLock {
                            replaceSourceLocked(source.id) { it.copy(updatedAt = clock(), lastAttemptAt = clock(), lastError = null) }
                        }
                    }
                    is Outcome.Failed -> {
                        failed++
                        gate.withLock {
                            replaceSourceLocked(source.id) { it.copy(lastAttemptAt = clock(), lastError = outcome.message) }
                        }
                    }
                }
            }
        } finally {
            setUpdating(false)
        }
        AppLog.i("blocklist", "update updated=$updated unchanged=$unchanged failed=$failed entries=${_matcher.value.totalEntries}")
        BlocklistUpdateSummary(updated, unchanged, failed)
    }

    private sealed interface Outcome {
        class Updated(val list: DomainBlocklist, val etag: String?, val lastModified: String?) : Outcome
        data object NotModified : Outcome
        class Failed(val message: String) : Outcome
    }

    private fun download(source: BlocklistSourceState): Outcome {
        val binary = File(directory, "${source.id}.bin")
        val hasData = binary.isFile && source.updatedAt > 0
        var lastError = "no url"
        for (address in source.urls) {
            val url = runCatching { URL(address) }.getOrNull()
            if (url == null || !isAllowedUrl(url)) {
                lastError = "invalid url"
                continue
            }
            var connection: HttpURLConnection? = null
            try {
                connection = open(url).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    // 只對同一個地址重用快取驗證資訊；換了鏡像就完整下載。
                    if (hasData && address == source.urls.first()) {
                        source.etag?.let { setRequestProperty("If-None-Match", it) }
                        source.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
                    }
                }
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED && hasData) return Outcome.NotModified
                if (code !in 200..299) {
                    lastError = "HTTP $code"
                    continue
                }
                val result = CappedInputStream(connection.inputStream, MAX_DOWNLOAD_BYTES).use { BlocklistParser.parse(it) }
                if (result.list.size == 0) {
                    lastError = "no domains found"
                    continue
                }
                result.list.writeTo(binary)
                val sameAddress = address == source.urls.first()
                return Outcome.Updated(
                    result.list,
                    etag = if (sameAddress) connection.getHeaderField("ETag") else null,
                    lastModified = if (sameAddress) connection.getHeaderField("Last-Modified") else null,
                )
            } catch (ex: IOException) {
                lastError = ex.message ?: ex.javaClass.simpleName
                AppLog.w("blocklist", "${source.id} from ${url.host} failed: $lastError")
            } finally {
                connection?.disconnect()
            }
        }
        return Outcome.Failed(lastError)
    }

    private fun isDue(source: BlocklistSourceState, intervalDays: Int, now: Long): Boolean {
        if (source.updatedAt == 0L) return now - source.lastAttemptAt > RETRY_FAILED_MS || source.lastAttemptAt == 0L
        if (source.lastError != null && now - source.lastAttemptAt < RETRY_FAILED_MS) return false
        return now - source.updatedAt >= intervalDays * DAY_MS
    }

    private fun isAllowedUrl(url: URL): Boolean = when (url.protocol.lowercase()) {
        "https" -> true
        "http" -> url.host == "127.0.0.1" || url.host == "localhost"
        else -> false
    }

    private fun setUpdating(value: Boolean) {
        _state.value = _state.value.copy(updating = value)
    }

    private suspend fun mutate(change: (BlocklistState) -> BlocklistState) = withContext(Dispatchers.IO) {
        gate.withLock { commitLocked(change(_state.value)) }
    }

    private fun replaceSourceLocked(id: String, change: (BlocklistSourceState) -> BlocklistSourceState) {
        commitLocked(_state.value.copy(sources = _state.value.sources.map { if (it.id == id) change(it) else it }))
    }

    private fun commitLocked(next: BlocklistState) {
        _state.value = next.copy(updating = _state.value.updating)
        runCatching { save(next) }.onFailure { AppLog.w("blocklist", "save failed: ${it.message}") }
        rebuildLocked()
    }

    private fun rebuildLocked() {
        val state = _state.value
        val lists = if (!state.enabled) {
            emptyList()
        } else {
            state.sources.filter { it.enabled }.mapNotNull { source ->
                val list = loaded[source.id] ?: runCatching {
                    File(directory, "${source.id}.bin").takeIf { it.isFile }?.let(DomainBlocklist::readFrom)
                }.getOrNull()?.also { loaded[source.id] = it }
                list?.let { source.id to it }
            }
        }
        // 已經不用的名單從記憶體釋放。
        val active = lists.map { it.first }.toSet()
        loaded.keys.retainAll(active)
        val previous = _matcher.value
        val next = BlocklistMatcher(lists)
        _matcher.value = next
        if (previous.totalEntries != next.totalEntries || previous.isEmpty != next.isEmpty) onChanged?.invoke()
    }

    private fun mergeBuiltin(state: BlocklistState): BlocklistState {
        val existing = state.sources.associateBy { it.id }
        val builtin = BlocklistCatalog.builtin.map { entry ->
            val saved = existing[entry.id]
            // 地址與名稱以目錄為準（新版本可能換了鏡像），使用者的勾選與更新狀態保留。
            saved?.copy(name = entry.name, urls = entry.urls, builtin = true)
                ?: BlocklistSourceState(entry.id, entry.name, entry.urls, builtin = true, enabled = false)
        }
        val custom = state.sources.filter { !it.builtin && BlocklistCatalog.find(it.id) == null }
        return state.copy(sources = builtin + custom)
    }

    private fun load(): BlocklistState {
        if (!stateFile.isFile) return BlocklistState()
        return runCatching { BlocklistStateCodec.decode(stateFile.readText()) }
            .onFailure { AppLog.w("blocklist", "state unreadable: ${it.message}") }
            .getOrDefault(BlocklistState())
    }

    private fun save(state: BlocklistState) {
        val temp = File(directory, "state.json.tmp")
        temp.writeText(BlocklistStateCodec.encode(state))
        if (!temp.renameTo(stateFile)) {
            stateFile.delete()
            temp.renameTo(stateFile)
        }
    }

    /** 防止伺服器回傳異常巨大的內容耗盡記憶體 / 儲存空間。 */
    private class CappedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0 && ++count > limit) throw IOException("download too large")
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                count += read
                if (count > limit) throw IOException("download too large")
            }
            return read
        }
    }

    companion object {
        private const val USER_AGENT = "NetworkControl-Android"
        private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val RETRY_FAILED_MS = 6L * 60 * 60 * 1000
        private const val RETRY_MISSING_MS = 60_000L
        private const val START_DELAY_MS = 30_000L
        private const val CHECK_INTERVAL_MS = 60L * 60 * 1000
    }
}

object BlocklistStateCodec {
    fun encode(state: BlocklistState): String = JSONObject().apply {
        put("version", 1)
        put("enabled", state.enabled)
        put("wifiOnly", state.wifiOnly)
        put("intervalDays", state.intervalDays)
        put("initialized", state.initialized)
        put(
            "sources",
            JSONArray().apply {
                state.sources.forEach { source ->
                    put(
                        JSONObject().apply {
                            put("id", source.id)
                            put("name", source.name)
                            put("urls", JSONArray(source.urls))
                            put("builtin", source.builtin)
                            put("enabled", source.enabled)
                            put("imported", source.imported)
                            put("entries", source.entries)
                            put("updatedAt", source.updatedAt)
                            put("lastAttemptAt", source.lastAttemptAt)
                            source.lastError?.let { put("lastError", it) }
                            source.etag?.let { put("etag", it) }
                            source.lastModified?.let { put("lastModified", it) }
                        },
                    )
                }
            },
        )
    }.toString()

    fun decode(text: String): BlocklistState {
        val root = JSONObject(text)
        val sources = root.optJSONArray("sources") ?: JSONArray()
        return BlocklistState(
            enabled = root.optBoolean("enabled", false),
            wifiOnly = root.optBoolean("wifiOnly", true),
            intervalDays = root.optInt("intervalDays", 7),
            initialized = root.optBoolean("initialized", false),
            sources = (0 until sources.length()).map { index ->
                val item = sources.getJSONObject(index)
                val urls = item.optJSONArray("urls") ?: JSONArray()
                BlocklistSourceState(
                    id = item.getString("id"),
                    name = item.optString("name", item.getString("id")),
                    urls = (0 until urls.length()).map(urls::getString),
                    builtin = item.optBoolean("builtin", false),
                    enabled = item.optBoolean("enabled", false),
                    imported = item.optBoolean("imported", false),
                    entries = item.optInt("entries", 0),
                    updatedAt = item.optLong("updatedAt", 0),
                    lastAttemptAt = item.optLong("lastAttemptAt", 0),
                    lastError = item.optString("lastError").takeIf { item.has("lastError") },
                    etag = item.optString("etag").takeIf { item.has("etag") },
                    lastModified = item.optString("lastModified").takeIf { item.has("lastModified") },
                )
            },
        )
    }
}
