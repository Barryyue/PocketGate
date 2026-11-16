package com.networkcontrol.android.kotlin.core.rules

import com.networkcontrol.android.kotlin.core.model.RemoteTarget
import com.networkcontrol.android.kotlin.core.model.Rule
import java.util.Locale

/**
 * 規則的語意鍵：相同鍵代表同一條規則，應該更新而不是重複新增。
 * 應用路徑在 Android 上大小寫不敏感（套件名稱），其他平台路徑保持原樣。
 * 連線條件會正規化排序，避免集合順序造成重複。
 */
object RuleIdentity {
    fun appPathKey(path: String): String =
        if (path.startsWith("android-app://", ignoreCase = true)) {
            path.trim().lowercase(Locale.ROOT)
        } else {
            path.trim()
        }

    fun key(rule: Rule): String = buildString {
        append(rule.profileId.orEmpty())
        append('|')
        val app = rule.app
        if (app == null) {
            append('*')
        } else {
            append(appPathKey(app.pathPattern))
            append('|')
            append(app.requiredSignerId.orEmpty())
            append('|')
            append(app.requiredSha256.orEmpty())
            append('|')
            append(app.serviceName.orEmpty())
            append('|')
            append(app.packageId.orEmpty())
        }
        append('|')
        append(conditionsKey(rule))
    }

    private fun conditionsKey(rule: Rule): String {
        val c = rule.conditions
        return buildString {
            append(c.direction?.name.orEmpty())
            append('|')
            append(c.protocol?.name.orEmpty())
            append('|')
            append(
                when (val remote = c.remote) {
                    is RemoteTarget.Domain -> "d:${remote.pattern.trim().lowercase(Locale.ROOT)}"
                    is RemoteTarget.Address ->
                        "a:" + remote.networks.map { it.toString() }.sorted().joinToString(",")
                    is RemoteTarget.Zone -> "z:${remote.zone.name}"
                    null -> ""
                },
            )
            append('|')
            append(c.remotePort?.toString().orEmpty())
            append('|')
            append(c.localPort?.toString().orEmpty())
            append('|')
            append(c.networkKinds.sortedBy { it.code }.joinToString(",") { it.name })
            append('|')
            append(c.subscriptionIds.sorted().joinToString(","))
            append('|')
            append(c.simSlots.sorted().joinToString(","))
        }
    }

    /**
     * 保留舊規則的 id，套用新規則的內容。
     * 永久規則保留原建立時間；臨時規則（僅一次、限時等）的有效期從建立時間起算，
     * 沿用舊時間會讓新答覆的「允許一次」立刻被當成過期回收。
     */
    fun merge(existing: Rule, incoming: Rule, now: Long): Rule = existing.copy(
        createdAt = if (incoming.lifetime == com.networkcontrol.android.kotlin.core.model.RuleLifetime.Permanent) {
            existing.createdAt
        } else {
            incoming.createdAt
        },
        app = incoming.app,
        conditions = incoming.conditions,
        action = incoming.action,
        lifetime = incoming.lifetime,
        expiresAt = incoming.expiresAt,
        boundProcess = incoming.boundProcess,
        source = incoming.source,
        enabled = incoming.enabled,
        manualPriority = incoming.manualPriority,
        note = incoming.note,
        updatedAt = now,
    )
}
