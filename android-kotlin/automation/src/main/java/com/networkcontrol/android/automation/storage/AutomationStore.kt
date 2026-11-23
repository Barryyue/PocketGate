package com.networkcontrol.android.automation.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.networkcontrol.android.automation.model.GroupOverride
import com.networkcontrol.android.automation.model.ParsedSubscription
import com.networkcontrol.android.automation.model.RawSubscription
import com.networkcontrol.android.automation.model.SubscriptionRecord
import com.networkcontrol.android.automation.model.TriggerLogEntry
import com.networkcontrol.android.automation.parser.GkdSubscriptionParser
import java.io.File

/**
 * 獨立於 `config.db` / `activity.db` 之外的自動化資料庫（`automation.db`），
 * 啟用 WAL 模式以支援主進程 UI 與 `:automation` 無障礙進程並發安全讀寫。
 */
class AutomationDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION,
) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        // Android SQLite 的 execSQL() 禁止執行會回傳結果列的 PRAGMA，必須使用 rawQuery。
        db.rawQuery("PRAGMA busy_timeout = 3000", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE subscriptions (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                author TEXT,
                version INTEGER NOT NULL,
                update_url TEXT,
                check_update_url TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                app_count INTEGER NOT NULL DEFAULT 0,
                group_count INTEGER NOT NULL DEFAULT 0,
                skipped_rule_count INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE group_overrides (
                subscription_id INTEGER NOT NULL,
                app_id TEXT NOT NULL,
                group_key INTEGER NOT NULL,
                enabled INTEGER NOT NULL,
                PRIMARY KEY (subscription_id, app_id, group_key)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE trigger_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_ms INTEGER NOT NULL,
                subscription_id INTEGER NOT NULL,
                subscription_name TEXT NOT NULL,
                app_id TEXT NOT NULL,
                activity_id TEXT,
                group_key INTEGER NOT NULL,
                group_name TEXT NOT NULL,
                rule_key INTEGER,
                action TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_trigger_logs_ts ON trigger_logs(timestamp_ms DESC)")
        db.execSQL(
            """
            CREATE TABLE settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val DB_NAME = "automation.db"
        const val DB_VERSION = 1
    }
}

/**
 * 自動化儲存層：元資訊與紀錄存於 `automation.db`，大型訂閱 JSON5 存於
 * `filesDir/automation/sub_<id>.json5`，避免 SQLite 單行超過 CursorWindow 上限。
 */
class AutomationStore(context: Context) {
    private val appContext = context.applicationContext
    private val dbHelper = AutomationDatabase(appContext)
    private val subDir = File(appContext.filesDir, "automation").apply { mkdirs() }

    private fun subFile(id: Long): File = File(subDir, "sub_$id.json5")

    // --- 實驗功能開關（跨進程共享，直接讀寫 automation.db） ---

    var featureEnabled: Boolean
        get() = getSetting(KEY_FEATURE_ENABLED) == "1"
        set(value) = setSetting(KEY_FEATURE_ENABLED, if (value) "1" else "0")

    var shizukuKeepAliveEnabled: Boolean
        get() = getSetting(KEY_SHIZUKU_KEEP_ALIVE) == "1"
        set(value) = setSetting(KEY_SHIZUKU_KEEP_ALIVE, if (value) "1" else "0")

    private fun getSetting(key: String): String? {
        val db = dbHelper.readableDatabase
        return db.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            "settings",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // --- 內建預設訂閱（出庫即帶，可正常更新或刪除，刪除後不強制復活） ---

    /** 首次使用時自動載入內建的離線社群訂閱快照（僅執行一次）。 */
    @Synchronized
    fun ensureDefaultSubscriptionSeeded(): Boolean {
        if (getSetting(KEY_DEFAULT_SUB_SEEDED) == "1") return false
        if (listSubscriptions().isNotEmpty()) {
            setSetting(KEY_DEFAULT_SUB_SEEDED, "1")
            return false
        }
        val ok = restoreDefaultSubscription().isSuccess
        if (ok) {
            setSetting(KEY_DEFAULT_SUB_SEEDED, "1")
        }
        return ok
    }

    /** 從 APK assets 重新匯入內建訂閱（供首次種入或使用者誤刪後一鍵還原）。 */
    fun restoreDefaultSubscription(): Result<SubscriptionRecord> = runCatching {
        val rawText = appContext.assets.open(DEFAULT_SUBSCRIPTION_ASSET).use { input ->
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val parsed = GkdSubscriptionParser.parse(rawText, validateSelectors = true)
        saveSubscription(
            parsed = parsed,
            rawJson5 = rawText,
            updateUrl = DEFAULT_SUBSCRIPTION_URL,
            enabled = true,
        )
    }.onFailure { err ->
        android.util.Log.w("AutomationStore", "Failed to restore default subscription", err)
    }

    // --- 訂閱 CRUD ---

    fun listSubscriptions(): List<SubscriptionRecord> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<SubscriptionRecord>()
        db.rawQuery(
            """
            SELECT id, name, author, version, update_url, check_update_url,
                   enabled, app_count, group_count, skipped_rule_count, updated_at_ms
            FROM subscriptions
            ORDER BY updated_at_ms DESC
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SubscriptionRecord(
                        id = c.getLong(0),
                        name = c.getString(1),
                        author = c.getString(2),
                        version = c.getInt(3),
                        updateUrl = c.getString(4),
                        checkUpdateUrl = c.getString(5),
                        enabled = c.getInt(6) != 0,
                        appCount = c.getInt(7),
                        groupCount = c.getInt(8),
                        skippedRuleCount = c.getInt(9),
                        updatedAtMs = c.getLong(10),
                    ),
                )
            }
        }
        return out
    }

    fun saveSubscription(
        parsed: ParsedSubscription,
        rawJson5: String,
        updateUrl: String?,
        enabled: Boolean? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): SubscriptionRecord {
        val sub = parsed.subscription
        val existingEnabled = listSubscriptions().firstOrNull { it.id == sub.id }?.enabled
        val finalEnabled = enabled ?: existingEnabled ?: true

        // 先寫入臨時檔再原子替換，避免讀到半截檔案
        val target = subFile(sub.id)
        val tmp = File(subDir, "sub_${sub.id}.json5.tmp")
        tmp.writeText(rawJson5, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(rawJson5, Charsets.UTF_8)
            tmp.delete()
        }

        val cv = ContentValues().apply {
            put("id", sub.id)
            put("name", sub.name)
            put("author", sub.author)
            put("version", sub.version)
            put("update_url", updateUrl)
            put("check_update_url", sub.checkUpdateUrl)
            put("enabled", if (finalEnabled) 1 else 0)
            put("app_count", sub.apps.size)
            put("group_count", sub.totalGroupCount)
            put("skipped_rule_count", parsed.skippedRuleCount)
            put("updated_at_ms", nowMs)
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            "subscriptions",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return SubscriptionRecord(
            id = sub.id,
            name = sub.name,
            author = sub.author,
            version = sub.version,
            updateUrl = updateUrl,
            checkUpdateUrl = sub.checkUpdateUrl,
            enabled = finalEnabled,
            appCount = sub.apps.size,
            groupCount = sub.totalGroupCount,
            skippedRuleCount = parsed.skippedRuleCount,
            updatedAtMs = nowMs,
        )
    }

    fun setSubscriptionEnabled(id: Long, enabled: Boolean) {
        val cv = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        dbHelper.writableDatabase.update("subscriptions", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteSubscription(id: Long) {
        val db = dbHelper.writableDatabase
        db.delete("subscriptions", "id = ?", arrayOf(id.toString()))
        db.delete("group_overrides", "subscription_id = ?", arrayOf(id.toString()))
        subFile(id).delete()
    }

    fun loadParsedSubscription(id: Long): RawSubscription? {
        val file = subFile(id)
        if (!file.exists()) return null
        return runCatching {
            GkdSubscriptionParser.parse(file.readText(Charsets.UTF_8), validateSelectors = true).subscription
        }.getOrNull()
    }

    fun loadAllEnabledSubscriptions(): List<RawSubscription> {
        return listSubscriptions()
            .filter { it.enabled }
            .mapNotNull { loadParsedSubscription(it.id) }
    }

    // --- 規則組開關覆寫 ---

    fun listGroupOverrides(subscriptionId: Long? = null): List<GroupOverride> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<GroupOverride>()
        val sql = if (subscriptionId == null) {
            "SELECT subscription_id, app_id, group_key, enabled FROM group_overrides"
        } else {
            "SELECT subscription_id, app_id, group_key, enabled FROM group_overrides WHERE subscription_id = ?"
        }
        val args = if (subscriptionId == null) null else arrayOf(subscriptionId.toString())
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(
                    GroupOverride(
                        subscriptionId = c.getLong(0),
                        appId = c.getString(1),
                        groupKey = c.getInt(2),
                        enabled = c.getInt(3) != 0,
                    ),
                )
            }
        }
        return out
    }

    fun setGroupOverride(subscriptionId: Long, appId: String, groupKey: Int, enabled: Boolean) {
        val cv = ContentValues().apply {
            put("subscription_id", subscriptionId)
            put("app_id", appId)
            put("group_key", groupKey)
            put("enabled", if (enabled) 1 else 0)
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            "group_overrides",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // --- 觸發紀錄 ---

    fun insertTriggerLog(
        subscriptionId: Long,
        subscriptionName: String,
        appId: String,
        activityId: String?,
        groupKey: Int,
        groupName: String,
        ruleKey: Int?,
        action: String,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("timestamp_ms", timestampMs)
            put("subscription_id", subscriptionId)
            put("subscription_name", subscriptionName)
            put("app_id", appId)
            put("activity_id", activityId)
            put("group_key", groupKey)
            put("group_name", groupName)
            put("rule_key", ruleKey)
            put("action", action)
        }
        db.insert("trigger_logs", null, cv)
        // 只保留最近 500 筆紀錄，避免資料庫膨脹
        db.execSQL(
            """
            DELETE FROM trigger_logs WHERE id NOT IN (
                SELECT id FROM trigger_logs ORDER BY timestamp_ms DESC LIMIT 500
            )
            """.trimIndent(),
        )
    }

    fun listTriggerLogs(limit: Int = 100): List<TriggerLogEntry> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<TriggerLogEntry>()
        db.rawQuery(
            """
            SELECT id, timestamp_ms, subscription_id, subscription_name,
                   app_id, activity_id, group_key, group_name, rule_key, action
            FROM trigger_logs
            ORDER BY timestamp_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TriggerLogEntry(
                        id = c.getLong(0),
                        timestampMs = c.getLong(1),
                        subscriptionId = c.getLong(2),
                        subscriptionName = c.getString(3),
                        appId = c.getString(4),
                        activityId = c.getString(5),
                        groupKey = c.getInt(6),
                        groupName = c.getString(7),
                        ruleKey = if (c.isNull(8)) null else c.getInt(8),
                        action = c.getString(9),
                    ),
                )
            }
        }
        return out
    }

    fun clearTriggerLogs() {
        dbHelper.writableDatabase.delete("trigger_logs", null, null)
    }

    fun todayTriggerCount(nowMs: Long = System.currentTimeMillis()): Int {
        val startOfDay = nowMs - (nowMs % 86_400_000L)
        return dbHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM trigger_logs WHERE timestamp_ms >= ?",
            arrayOf(startOfDay.toString()),
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    companion object {
        private const val KEY_FEATURE_ENABLED = "feature_enabled"
        private const val KEY_SHIZUKU_KEEP_ALIVE = "shizuku_keep_alive"
        private const val KEY_DEFAULT_SUB_SEEDED = "default_sub_seeded"
        const val DEFAULT_SUBSCRIPTION_ASSET = "default_gkd.json5"
        const val DEFAULT_SUBSCRIPTION_URL = "https://gkd-subscription-667.pages.dev/gkd.json5"
    }
}
