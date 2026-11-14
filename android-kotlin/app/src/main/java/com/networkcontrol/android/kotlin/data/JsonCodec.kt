package com.networkcontrol.android.kotlin.data

import com.networkcontrol.android.kotlin.core.matching.IpNetwork
import com.networkcontrol.android.kotlin.core.model.AppMatcher
import com.networkcontrol.android.kotlin.core.model.AutoActivateCondition
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.model.NetworkZone
import com.networkcontrol.android.kotlin.core.model.PortRange
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.RemoteTarget
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.RuntimeState
import com.networkcontrol.android.kotlin.core.model.UserSettings
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import org.json.JSONArray
import org.json.JSONObject

object JsonCodec {
    fun settingsToJson(settings: UserSettings): String = JSONObject().apply {
        put("promptTimeoutSeconds", settings.promptTimeoutSeconds)
        put("promptDefaultAction", settings.promptDefaultAction.name)
        put("maxPromptLifetimeSeconds", settings.maxPromptLifetimeSeconds)
        put("onceFallbackSeconds", settings.onceFallbackSeconds)
        put("maxPendingFlows", settings.maxPendingFlows)
        put("allowIcmpByDefault", settings.allowIcmpByDefault)
        put("logRetentionDays", settings.logRetentionDays)
        put("allowSystemAppsByDefault", settings.allowSystemAppsByDefault)
        put("trustedSigners", JSONArray(settings.trustedSigners.toList()))
    }.toString()

    fun settingsFromJson(json: String): UserSettings {
        val obj = JSONObject(json)
        return UserSettings(
            promptTimeoutSeconds = obj.optInt("promptTimeoutSeconds", 30),
            promptDefaultAction = enumOr(obj, "promptDefaultAction", VerdictAction.Deny),
            maxPromptLifetimeSeconds = obj.optInt("maxPromptLifetimeSeconds", 300),
            onceFallbackSeconds = obj.optInt("onceFallbackSeconds", 60),
            maxPendingFlows = obj.optInt("maxPendingFlows", 1000),
            allowIcmpByDefault = obj.optBoolean("allowIcmpByDefault", true),
            logRetentionDays = obj.optInt("logRetentionDays", 30),
            allowSystemAppsByDefault = obj.optBoolean("allowSystemAppsByDefault", false),
            trustedSigners = obj.optJSONArray("trustedSigners")?.toStringList()?.toSet() ?: emptySet(),
        ).validated()
    }

    fun runtimeToJson(state: RuntimeState): String = JSONObject().apply {
        put("activeProfileId", state.activeProfileId)
        put("mode", state.mode.name)
        put("modeBeforeBlockAll", state.modeBeforeBlockAll?.name ?: JSONObject.NULL)
        put("doNotDisturbUntil", state.doNotDisturbUntil ?: JSONObject.NULL)
    }.toString()

    fun runtimeFromJson(json: String): RuntimeState {
        val obj = JSONObject(json)
        return RuntimeState(
            activeProfileId = obj.optString("activeProfileId", Profile.DEFAULT_ID),
            mode = enumOr(obj, "mode", FilterMode.Ask),
            modeBeforeBlockAll = enumOrNull<FilterMode>(obj, "modeBeforeBlockAll"),
            doNotDisturbUntil = if (obj.isNull("doNotDisturbUntil")) null else obj.optLong("doNotDisturbUntil"),
        )
    }

    fun appMatcherToJson(matcher: AppMatcher?): String? = matcher?.let {
        JSONObject().apply {
            put("pathPattern", it.pathPattern)
            put("requiredSignerId", it.requiredSignerId ?: JSONObject.NULL)
            put("requiredSha256", it.requiredSha256 ?: JSONObject.NULL)
            put("serviceName", it.serviceName ?: JSONObject.NULL)
            put("packageId", it.packageId ?: JSONObject.NULL)
        }.toString()
    }

    fun appMatcherFromJson(json: String?): AppMatcher? {
        if (json.isNullOrBlank()) return null
        val obj = JSONObject(json)
        return AppMatcher(
            pathPattern = obj.getString("pathPattern"),
            requiredSignerId = obj.stringOrNull("requiredSignerId"),
            requiredSha256 = obj.stringOrNull("requiredSha256"),
            serviceName = obj.stringOrNull("serviceName"),
            packageId = obj.stringOrNull("packageId"),
        )
    }

    fun conditionsToJson(conditions: RuleConditions): String = JSONObject().apply {
        put("direction", conditions.direction?.name ?: JSONObject.NULL)
        put("protocol", conditions.protocol?.name ?: JSONObject.NULL)
        put("remote", remoteToJson(conditions.remote) ?: JSONObject.NULL)
        put("remotePort", portToJson(conditions.remotePort) ?: JSONObject.NULL)
        put("localPort", portToJson(conditions.localPort) ?: JSONObject.NULL)
        put("networkKinds", JSONArray(conditions.networkKinds.map { it.name }))
        put("subscriptionIds", JSONArray(conditions.subscriptionIds.toList()))
        put("simSlots", JSONArray(conditions.simSlots.toList()))
    }.toString()

    fun conditionsFromJson(json: String): RuleConditions {
        val obj = JSONObject(json)
        return RuleConditions(
            direction = enumOrNull<Direction>(obj, "direction"),
            protocol = enumOrNull<L4Protocol>(obj, "protocol"),
            remote = obj.optJSONObject("remote")?.let(::remoteFromJson),
            remotePort = obj.optJSONObject("remotePort")?.let(::portFromJson),
            localPort = obj.optJSONObject("localPort")?.let(::portFromJson),
            networkKinds = obj.optJSONArray("networkKinds")?.toStringList()
                ?.mapNotNull { name -> NetworkKind.entries.firstOrNull { it.name == name } }
                ?.toSet()
                ?: emptySet(),
            subscriptionIds = obj.optJSONArray("subscriptionIds")?.let { array ->
                (0 until array.length()).map { array.getInt(it) }.toSet()
            } ?: emptySet(),
            simSlots = obj.optJSONArray("simSlots")?.let { array ->
                (0 until array.length()).map { array.getInt(it) }.toSet()
            } ?: emptySet(),
        )
    }

    fun ruleToJson(rule: Rule, includeStats: Boolean = false): String = JSONObject().apply {
        put("id", rule.id)
        put("profileId", rule.profileId ?: JSONObject.NULL)
        put("app", appMatcherToJson(rule.app)?.let(::JSONObject) ?: JSONObject.NULL)
        put("conditions", JSONObject(conditionsToJson(rule.conditions)))
        put("action", rule.action.name)
        put("lifetime", rule.lifetime.name)
        put("expiresAt", rule.expiresAt ?: JSONObject.NULL)
        put("source", rule.source.name)
        put("enabled", rule.enabled)
        put("manualPriority", rule.manualPriority)
        put("note", rule.note ?: JSONObject.NULL)
        put("createdAt", rule.createdAt)
        put("updatedAt", rule.updatedAt)
        if (includeStats) {
            put("lastHitAt", rule.lastHitAt ?: JSONObject.NULL)
            put("hitCount", rule.hitCount)
        }
    }.toString()

    fun ruleFromJson(json: String, hitCount: Long = 0, lastHitAt: Long? = null): Rule {
        val obj = JSONObject(json)
        return Rule(
            id = obj.getString("id"),
            profileId = obj.stringOrNull("profileId"),
            app = obj.optString("app").takeIf { it.isNotBlank() && it != "null" }?.let(::appMatcherFromJson),
            conditions = conditionsFromJson(obj.getJSONObject("conditions").toString()),
            action = enumOr(obj, "action", VerdictAction.Deny),
            lifetime = enumOr(obj, "lifetime", RuleLifetime.Permanent),
            expiresAt = if (obj.isNull("expiresAt")) null else obj.optLong("expiresAt"),
            source = enumOr(obj, "source", RuleSource.User),
            enabled = obj.optBoolean("enabled", true),
            manualPriority = obj.optInt("manualPriority", 0),
            note = obj.stringOrNull("note"),
            createdAt = obj.optLong("createdAt"),
            updatedAt = obj.optLong("updatedAt"),
            lastHitAt = if (obj.isNull("lastHitAt")) lastHitAt else obj.optLong("lastHitAt"),
            hitCount = if (obj.has("hitCount")) obj.optLong("hitCount") else hitCount,
        )
    }

    fun autoActivateToJson(condition: AutoActivateCondition?): String? = condition?.let {
        JSONObject().apply {
            put("kind", it.kind?.name ?: JSONObject.NULL)
            put("ssids", JSONArray(it.ssids))
            put("networkIds", JSONArray(it.networkIds))
            put("subscriptionIds", JSONArray(it.subscriptionIds))
            put("simSlots", JSONArray(it.simSlots))
        }.toString()
    }

    fun autoActivateFromJson(json: String?): AutoActivateCondition? {
        if (json.isNullOrBlank()) return null
        val obj = JSONObject(json)
        return AutoActivateCondition(
            kind = enumOrNull<NetworkKind>(obj, "kind"),
            ssids = obj.optJSONArray("ssids")?.toStringList() ?: emptyList(),
            networkIds = obj.optJSONArray("networkIds")?.toStringList() ?: emptyList(),
            subscriptionIds = obj.optJSONArray("subscriptionIds")?.let { array ->
                (0 until array.length()).map { array.getInt(it) }
            } ?: emptyList(),
            simSlots = obj.optJSONArray("simSlots")?.let { array ->
                (0 until array.length()).map { array.getInt(it) }
            } ?: emptyList(),
        )
    }

    private fun remoteToJson(remote: RemoteTarget?): JSONObject? = when (remote) {
        is RemoteTarget.Domain -> JSONObject()
            .put("kind", "domain")
            .put("pattern", remote.pattern)
        is RemoteTarget.Address -> JSONObject()
            .put("kind", "address")
            .put("networks", JSONArray(remote.networks.map { it.toString() }))
        is RemoteTarget.Zone -> JSONObject()
            .put("kind", "zone")
            .put("zone", remote.zone.name)
        null -> null
    }

    private fun remoteFromJson(obj: JSONObject): RemoteTarget? = when (obj.optString("kind")) {
        "domain" -> RemoteTarget.Domain(obj.getString("pattern"))
        "address" -> RemoteTarget.Address(
            obj.getJSONArray("networks").toStringList().map { IpNetwork.parse(it) },
        )
        "zone" -> RemoteTarget.Zone(enumOr(obj, "zone", NetworkZone.Internet))
        else -> null
    }

    private fun portToJson(range: PortRange?): JSONObject? = range?.let {
        JSONObject().put("from", it.from).put("to", it.to)
    }

    private fun portFromJson(obj: JSONObject): PortRange =
        PortRange(obj.getInt("from"), obj.getInt("to"))

    inline fun <reified T : Enum<T>> enumOr(obj: JSONObject, key: String, fallback: T): T {
        val value = obj.optString(key)
        return runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
    }

    inline fun <reified T : Enum<T>> enumOrNull(obj: JSONObject, key: String): T? {
        if (obj.isNull(key)) return null
        val value = obj.optString(key)
        return runCatching { enumValueOf<T>(value) }.getOrNull()
    }

    fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
}
