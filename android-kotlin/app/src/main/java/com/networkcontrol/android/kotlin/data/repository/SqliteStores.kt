package com.networkcontrol.android.kotlin.data.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.CodeSignature
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleEndReason
import com.networkcontrol.android.kotlin.core.model.RuntimeState
import com.networkcontrol.android.kotlin.core.model.UserSettings
import com.networkcontrol.android.kotlin.core.storage.AppStore
import com.networkcontrol.android.kotlin.core.storage.ProfileStore
import com.networkcontrol.android.kotlin.core.storage.RuleHitDelta
import com.networkcontrol.android.kotlin.core.storage.RuleStore
import com.networkcontrol.android.kotlin.core.storage.SettingsStore
import com.networkcontrol.android.kotlin.data.JsonCodec
import com.networkcontrol.android.kotlin.data.db.ConfigDatabase
import com.networkcontrol.android.kotlin.data.db.inTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private fun Cursor.text(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}

class SqliteSettingsStore(private val db: ConfigDatabase) : SettingsStore {
    override suspend fun loadUserSettings(): UserSettings? = withContext(Dispatchers.IO) {
        read("user")?.let { runCatching { JsonCodec.settingsFromJson(it) }.getOrNull() }
    }

    override suspend fun saveUserSettings(settings: UserSettings) = withContext(Dispatchers.IO) {
        write("user", JsonCodec.settingsToJson(settings))
    }

    override suspend fun loadRuntimeState(): RuntimeState? = withContext(Dispatchers.IO) {
        read("runtime")?.let { runCatching { JsonCodec.runtimeFromJson(it) }.getOrNull() }
    }

    override suspend fun saveRuntimeState(state: RuntimeState) = withContext(Dispatchers.IO) {
        write("runtime", JsonCodec.runtimeToJson(state))
    }

    private fun read(key: String): String? =
        db.readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?;", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun write(key: String, value: String) {
        db.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?);",
            arrayOf<Any?>(key, value),
        )
    }
}

class SqliteRuleStore(private val db: ConfigDatabase) : RuleStore {
    override suspend fun loadAll(): List<Rule> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            """
            SELECT id, profile_id, app_matcher, conditions, action, lifetime, expires_at, bound_pid, bound_start,
                   source, enabled, manual_priority, note, created_at, updated_at, last_hit_at, hit_count
            FROM rules;
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toRule())
            }
        }
    }

    override suspend fun upsert(rule: Rule) = withContext(Dispatchers.IO) {
        val values = rule.toValues()
        db.writableDatabase.inTransaction {
            insertWithOnConflict("rules", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            update("rules", values.apply { remove("id") }, "id=?", arrayOf(rule.id))
        }
    }

    override suspend fun delete(rules: List<Rule>, reason: RuleEndReason, endedAt: Long) =
        withContext(Dispatchers.IO) {
            if (rules.isEmpty()) return@withContext
            db.writableDatabase.inTransaction {
                rules.forEach { rule ->
                    val history = ContentValues().apply {
                        put("id", rule.id)
                        put("snapshot", JsonCodec.ruleToJson(rule, includeStats = false))
                        put("ended_at", endedAt)
                        put("end_reason", reason.code)
                    }
                    insertWithOnConflict("rule_history", null, history, SQLiteDatabase.CONFLICT_REPLACE)
                    delete("rules", "id=?", arrayOf(rule.id))
                }
            }
        }

    override suspend fun recordHits(hits: List<RuleHitDelta>) = withContext(Dispatchers.IO) {
        if (hits.isEmpty()) return@withContext
        db.writableDatabase.inTransaction {
            hits.forEach { hit ->
                execSQL(
                    "UPDATE rules SET hit_count = hit_count + ?, last_hit_at = MAX(COALESCE(last_hit_at, 0), ?) WHERE id = ?;",
                    arrayOf<Any?>(hit.count, hit.lastHitAt, hit.ruleId),
                )
            }
        }
    }

    private fun Rule.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("profile_id", profileId)
        put("app_matcher", JsonCodec.appMatcherToJson(app))
        put("conditions", JsonCodec.conditionsToJson(conditions))
        put("action", action.code)
        put("lifetime", lifetime.code)
        put("expires_at", expiresAt)
        put("bound_pid", boundProcess?.pid)
        put("bound_start", boundProcess?.startTimeTicks)
        put("source", source.code)
        put("enabled", if (enabled) 1 else 0)
        put("manual_priority", manualPriority)
        put("note", note)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
    }

    private fun Cursor.toRule(): Rule {
        val appJson = text("app_matcher")
        val conditions = JsonCodec.conditionsFromJson(getString(getColumnIndexOrThrow("conditions")))
        val pid = longOrNull("bound_pid")
        val start = longOrNull("bound_start")
        return Rule(
            id = getString(getColumnIndexOrThrow("id")),
            profileId = text("profile_id"),
            app = JsonCodec.appMatcherFromJson(appJson),
            conditions = conditions,
            action = com.networkcontrol.android.kotlin.core.model.VerdictAction.fromCode(getInt(getColumnIndexOrThrow("action"))),
            lifetime = com.networkcontrol.android.kotlin.core.model.RuleLifetime.fromCode(getInt(getColumnIndexOrThrow("lifetime"))),
            expiresAt = longOrNull("expires_at"),
            boundProcess = if (pid != null && start != null) {
                com.networkcontrol.android.kotlin.core.model.ProcessRef(pid.toInt(), start)
            } else {
                null
            },
            source = com.networkcontrol.android.kotlin.core.model.RuleSource.fromCode(getInt(getColumnIndexOrThrow("source"))),
            enabled = getInt(getColumnIndexOrThrow("enabled")) != 0,
            manualPriority = getInt(getColumnIndexOrThrow("manual_priority")),
            note = text("note"),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
            lastHitAt = longOrNull("last_hit_at"),
            hitCount = getLong(getColumnIndexOrThrow("hit_count")),
        )
    }
}

class SqliteProfileStore(private val db: ConfigDatabase) : ProfileStore {
    override suspend fun loadAll(): List<Profile> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT id, name, mode, auto_activate, sort_order FROM profiles ORDER BY sort_order, name;",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Profile(
                            id = cursor.getString(0),
                            name = cursor.getString(1),
                            mode = com.networkcontrol.android.kotlin.core.model.FilterMode.fromCode(cursor.getInt(2)),
                            autoActivate = JsonCodec.autoActivateFromJson(cursor.text("auto_activate")),
                            sortOrder = cursor.getInt(4),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun upsert(profile: Profile) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("mode", profile.mode.code)
                put("auto_activate", JsonCodec.autoActivateToJson(profile.autoActivate))
                put("sort_order", profile.sortOrder)
            }
            db.writableDatabase.insertWithOnConflict("profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            db.writableDatabase.delete("profiles", "id=?", arrayOf(id))
        }
    }
}

class SqliteAppStore(private val db: ConfigDatabase) : AppStore {
    override suspend fun upsert(identity: AppIdentity): AppIdentity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("path", identity.path)
            put("service_name", identity.serviceName.orEmpty())
            put("package_id", identity.packageId.orEmpty())
            put("display_name", identity.displayName)
            put("signer_id", identity.signature?.signerId)
            put("signing_id", identity.signature?.signingId)
            put("is_platform", if (identity.signature?.isPlatformBinary == true) 1 else 0)
            put("sha256", identity.sha256)
            put("first_seen", now)
            put("last_seen", now)
        }
        val database = db.writableDatabase
        database.inTransaction {
            insertWithOnConflict("apps", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            val update = ContentValues().apply {
                put("display_name", identity.displayName)
                put("signer_id", identity.signature?.signerId)
                put("signing_id", identity.signature?.signingId)
                put("is_platform", if (identity.signature?.isPlatformBinary == true) 1 else 0)
                put("sha256", identity.sha256)
                put("last_seen", now)
            }
            update(
                "apps",
                update,
                "path=? AND service_name=? AND package_id=?",
                arrayOf(identity.path, identity.serviceName.orEmpty(), identity.packageId.orEmpty()),
            )
        }
        val id = database.rawQuery(
            "SELECT id FROM apps WHERE path=? AND service_name=? AND package_id=?;",
            arrayOf(identity.path, identity.serviceName.orEmpty(), identity.packageId.orEmpty()),
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        identity.copy(appId = id)
    }

    override suspend fun get(appId: Long): AppIdentity? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT id, path, package_id, service_name, display_name, signer_id, signing_id, is_platform, sha256 FROM apps WHERE id=?;",
            arrayOf(appId.toString()),
        ).use { if (it.moveToFirst()) it.toIdentity() else null }
    }

    override suspend fun find(path: String): AppIdentity? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT id, path, package_id, service_name, display_name, signer_id, signing_id, is_platform, sha256 FROM apps WHERE path=? LIMIT 1;",
            arrayOf(path),
        ).use { if (it.moveToFirst()) it.toIdentity() else null }
    }

    private fun Cursor.toIdentity(): AppIdentity {
        val signer = text("signer_id")
        return AppIdentity(
            appId = getLong(getColumnIndexOrThrow("id")),
            path = getString(getColumnIndexOrThrow("path")),
            packageId = text("package_id"),
            serviceName = text("service_name"),
            displayName = text("display_name"),
            signature = signer?.let {
                CodeSignature(
                    signerId = it,
                    signingId = text("signing_id"),
                    isPlatformBinary = getInt(getColumnIndexOrThrow("is_platform")) != 0,
                )
            },
            sha256 = text("sha256"),
        )
    }
}

fun newRuleId(): String = UUID.randomUUID().toString()
