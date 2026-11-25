package com.networkcontrol.android.kotlin.data.transfer

import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleImportMode
import com.networkcontrol.android.kotlin.core.model.RuleImportPlan
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.rules.RuleIdentity
import com.networkcontrol.android.kotlin.data.JsonCodec
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

object RuleTransferService {
    private const val FORMAT = "netcontrol-rules"
    private const val VERSION = 1

    fun export(rules: List<Rule>): String {
        val now = Time.now()
        val exportable = rules.filter {
            it.source != RuleSource.Builtin &&
                (it.lifetime == RuleLifetime.Permanent ||
                    (it.lifetime == RuleLifetime.Timed && (it.expiresAt ?: 0) > now))
        }
        val array = JSONArray()
        exportable.forEach { array.put(toExportJson(it)) }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("rules", array)
        }.toString(2)
    }

    fun importForPlan(json: String, profiles: List<Profile>): List<Rule> {
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw IllegalArgumentException("規則文件不是有效的 JSON：${it.message}")
        }
        val format = root.optString("format")
        val version = root.optInt("version", -1)
        val isV1Rules = format == FORMAT && version == VERSION
        val isV2Snapshot = format == "pocketgate-backup" && version == 2
        if (!isV1Rules && !isV2Snapshot) {
            throw IllegalArgumentException("不支援的規則或備份格式：$format v$version。")
        }
        val rules = root.optJSONArray("rules") ?: throw IllegalArgumentException("缺少 rules。")
        val knownProfiles = profiles.map { it.id }.toSet()
        val now = Time.now()
        val result = ArrayList<Rule>(rules.length())
        for (i in 0 until rules.length()) {
            val obj = rules.getJSONObject(i)
            val rule = runCatching { fromExportJson(obj) }.getOrElse {
                throw IllegalArgumentException("第 ${i + 1} 條規則無效：${it.message}")
            }
            if (rule.lifetime == RuleLifetime.Timed && (rule.expiresAt ?: 0) <= now) continue
            result += if (rule.profileId != null && rule.profileId !in knownProfiles) {
                rule.copy(profileId = null)
            } else {
                rule
            }
        }
        return result
    }

    fun plan(current: List<Rule>, imported: List<Rule>, mode: RuleImportMode): RuleImportPlan {
        val localById = current.associateBy { it.id }
        val localByIdentity = current.associateBy { RuleIdentity.key(it) }
        val incoming = LinkedHashMap<String, Rule>()
        imported.forEach { incoming[it.id] = it }
        val toAdd = mutableListOf<Rule>()
        val toUpdate = mutableListOf<Rule>()
        var unchanged = 0
        val matchedCurrentIds = mutableSetOf<String>()

        incoming.values.forEach { rule ->
            val existing = localById[rule.id] ?: localByIdentity[RuleIdentity.key(rule)]
            if (existing == null) {
                toAdd += rule.copy(source = RuleSource.Import, boundProcess = null)
                return@forEach
            }
            matchedCurrentIds += existing.id
            if (existing.source == RuleSource.Builtin) {
                unchanged++
                return@forEach
            }
            val candidate = rule.copy(
                id = existing.id,
                source = existing.source,
                createdAt = existing.createdAt,
                updatedAt = if (differs(existing, rule)) Time.now() else existing.updatedAt,
                boundProcess = existing.boundProcess,
            )
            if (differs(existing, rule)) {
                toUpdate += candidate
            } else {
                unchanged++
            }
        }

        val toRemove = if (mode == RuleImportMode.Replace) {
            current.filter {
                it.source != RuleSource.Builtin &&
                    (it.lifetime == RuleLifetime.Permanent || it.lifetime == RuleLifetime.Timed) &&
                    it.id !in matchedCurrentIds
            }
        } else {
            emptyList()
        }

        return RuleImportPlan(toAdd, toUpdate, toRemove, unchanged)
    }

    private fun differs(a: Rule, b: Rule): Boolean =
        a.profileId != b.profileId ||
            JsonCodec.appMatcherToJson(a.app) != JsonCodec.appMatcherToJson(b.app) ||
            JsonCodec.conditionsToJson(a.conditions) != JsonCodec.conditionsToJson(b.conditions) ||
            a.enabled != b.enabled ||
            a.note != b.note ||
            a.manualPriority != b.manualPriority ||
            a.expiresAt != b.expiresAt ||
            a.lifetime != b.lifetime ||
            a.action != b.action

    private fun toExportJson(rule: Rule): JSONObject = JSONObject().apply {
        put("id", rule.id)
        if (rule.profileId != null) put("profileId", rule.profileId)
        JsonCodec.appMatcherToJson(rule.app)?.let { put("app", JSONObject(it)) }
        put("conditions", JSONObject(JsonCodec.conditionsToJson(rule.conditions)))
        put("action", rule.action.name)
        put("lifetime", rule.lifetime.name)
        rule.expiresAt?.let { put("expiresAt", iso(it)) }
        put("source", rule.source.name)
        put("enabled", rule.enabled)
        put("manualPriority", rule.manualPriority)
        rule.note?.let { put("note", it) }
        put("createdAt", iso(rule.createdAt))
        put("updatedAt", iso(rule.updatedAt))
    }

    private fun fromExportJson(obj: JSONObject): Rule {
        val action = enumValueOf<VerdictAction>(obj.getString("action"))
        val lifetime = enumValueOf<RuleLifetime>(obj.getString("lifetime"))
        val source = runCatching { enumValueOf<RuleSource>(obj.optString("source")) }
            .getOrDefault(RuleSource.Import)
        val app = obj.optJSONObject("app")?.let { JsonCodec.appMatcherFromJson(it.toString()) }
        val conditions = JsonCodec.conditionsFromJson(obj.getJSONObject("conditions").toString())
        val expiresAt = parseDate(obj.opt("expiresAt"))
        if (lifetime == RuleLifetime.Timed && expiresAt == null) {
            throw IllegalArgumentException("限時規則缺少 expiresAt。")
        }
        val now = Time.now()
        return Rule(
            id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            profileId = obj.stringOrNull("profileId"),
            app = app,
            conditions = conditions,
            action = action,
            lifetime = lifetime,
            expiresAt = expiresAt,
            source = source,
            enabled = obj.optBoolean("enabled", true),
            manualPriority = obj.optInt("manualPriority", 0),
            note = obj.stringOrNull("note"),
            createdAt = parseDate(obj.opt("createdAt")) ?: now,
            updatedAt = parseDate(obj.opt("updatedAt")) ?: now,
        )
    }

    private fun iso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun parseDate(value: Any?): Long? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toLong()
        is String -> runCatching {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        }
        else -> null
    }
}
