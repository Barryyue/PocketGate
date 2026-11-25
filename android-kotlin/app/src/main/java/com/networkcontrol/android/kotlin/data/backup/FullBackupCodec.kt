package com.networkcontrol.android.kotlin.data.backup

import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.UserSettings
import com.networkcontrol.android.kotlin.data.JsonCodec
import com.networkcontrol.android.kotlin.data.JsonCodec.toStringList
import com.networkcontrol.android.kotlin.data.transfer.RuleTransferService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 完整快照備份（`pocketgate-backup` v2）：
 * - 涵蓋規則、配置檔案（Profiles）、使用者設定（UserSettings）、可移植偏好設定（排除敏感密碼）與自動化訂閱清單。
 * - 向後相容 v1 `netcontrol-rules`（僅規則匯入）。
 */
data class PortablePrefsSnapshot(
    val forwardingEnabled: Boolean? = null,
    val forwardingUpstream: String? = null,
    val socks5Host: String? = null,
    val socks5Port: Int? = null,
    val socks5Udp: Boolean? = null,
    val socks5BypassPackage: String? = null,
    val forwardingDirectPackages: Set<String>? = null,
    val dnsBypassProtection: Boolean? = null,
    val exitDnsProvider: String? = null,
    val exitDnsCustomUrl: String? = null,
    val exitDnsFallback: Boolean? = null,
    val blockOnLockScreen: Boolean? = null,
    val lockScreenWhitelist: Set<String>? = null,
    val cloneAppsInheritPrimary: Boolean? = null,
    val focusSchedulesJson: String? = null,
    val autoHideBottomBar: Boolean? = null,
    val offlineMode: Boolean? = null,
    val foregroundOnlyPackages: Set<String>? = null,
    val forwardingSplitExitEnabled: Boolean? = null,
    val forwardingProxyPackages: Set<String>? = null,
)

data class FullBackupSnapshot(
    val format: String,
    val version: Int,
    val rulesJson: String,
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    val userSettings: UserSettings? = null,
    val portablePrefs: PortablePrefsSnapshot? = null,
    val automationSubscriptionUrls: List<String> = emptyList(),
) {
    val isFullSnapshot: Boolean get() = format == FullBackupCodec.FORMAT && version == FullBackupCodec.VERSION
}

object FullBackupCodec {
    const val FORMAT = "pocketgate-backup"
    const val VERSION = 2

    fun export(
        rules: List<Rule>,
        profiles: List<Profile>,
        activeProfileId: String,
        userSettings: UserSettings,
        portablePrefs: PortablePrefsSnapshot,
        automationSubscriptionUrls: List<String> = emptyList(),
    ): String {
        val rulesRoot = JSONObject(RuleTransferService.export(rules))
        val profilesArr = JSONArray()
        profiles.forEach { p ->
            profilesArr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("mode", p.mode.name)
                    put("sortOrder", p.sortOrder)
                    JsonCodec.autoActivateToJson(p.autoActivate)?.let { put("autoActivate", JSONObject(it)) }
                },
            )
        }
        val prefsObj = JSONObject().apply {
            portablePrefs.forwardingEnabled?.let { put("forwardingEnabled", it) }
            portablePrefs.forwardingUpstream?.let { put("forwardingUpstream", it) }
            portablePrefs.socks5Host?.let { put("socks5Host", it) }
            portablePrefs.socks5Port?.let { put("socks5Port", it) }
            portablePrefs.socks5Udp?.let { put("socks5Udp", it) }
            portablePrefs.socks5BypassPackage?.let { put("socks5BypassPackage", it) }
            portablePrefs.forwardingDirectPackages?.let { put("forwardingDirectPackages", JSONArray(it.sorted())) }
            portablePrefs.dnsBypassProtection?.let { put("dnsBypassProtection", it) }
            portablePrefs.exitDnsProvider?.let { put("exitDnsProvider", it) }
            portablePrefs.exitDnsCustomUrl?.let { put("exitDnsCustomUrl", it) }
            portablePrefs.exitDnsFallback?.let { put("exitDnsFallback", it) }
            portablePrefs.blockOnLockScreen?.let { put("blockOnLockScreen", it) }
            portablePrefs.lockScreenWhitelist?.let { put("lockScreenWhitelist", JSONArray(it.sorted())) }
            portablePrefs.cloneAppsInheritPrimary?.let { put("cloneAppsInheritPrimary", it) }
            portablePrefs.focusSchedulesJson?.let { put("focusSchedulesJson", it) }
            portablePrefs.autoHideBottomBar?.let { put("autoHideBottomBar", it) }
            portablePrefs.offlineMode?.let { put("offlineMode", it) }
            portablePrefs.foregroundOnlyPackages?.let { put("foregroundOnlyPackages", JSONArray(it.sorted())) }
            portablePrefs.forwardingSplitExitEnabled?.let { put("forwardingSplitExitEnabled", it) }
            portablePrefs.forwardingProxyPackages?.let { put("forwardingProxyPackages", JSONArray(it.sorted())) }
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("activeProfileId", activeProfileId)
            put("rules", rulesRoot.getJSONArray("rules"))
            put("profiles", profilesArr)
            put("userSettings", JSONObject(JsonCodec.settingsToJson(userSettings)))
            put("portablePrefs", prefsObj)
            if (automationSubscriptionUrls.isNotEmpty()) {
                put("automationSubscriptionUrls", JSONArray(automationSubscriptionUrls))
            }
        }.toString(2)
    }

    fun parse(json: String): FullBackupSnapshot {
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw IllegalArgumentException("備份檔案不是有效的 JSON：${it.message}")
        }
        val format = root.optString("format")
        val version = root.optInt("version", -1)
        if (format == "netcontrol-rules") {
            return FullBackupSnapshot(format = format, version = version, rulesJson = json)
        }
        if (format != FORMAT || version != VERSION) {
            throw IllegalArgumentException("不支援的備份格式：$format v$version")
        }
        val rulesArr = root.optJSONArray("rules") ?: JSONArray()
        val wrappedRulesJson = JSONObject().apply {
            put("format", "netcontrol-rules")
            put("version", 1)
            put("rules", rulesArr)
        }.toString()

        val profiles = mutableListOf<Profile>()
        val profilesArr = root.optJSONArray("profiles")
        if (profilesArr != null) {
            for (i in 0 until profilesArr.length()) {
                val obj = profilesArr.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
                val mode = runCatching { enumValueOf<FilterMode>(obj.optString("mode")) }
                    .getOrDefault(FilterMode.Ask)
                val sortOrder = obj.optInt("sortOrder", i)
                val auto = obj.optJSONObject("autoActivate")?.let { JsonCodec.autoActivateFromJson(it.toString()) }
                profiles += Profile(id = id, name = name, mode = mode, sortOrder = sortOrder, autoActivate = auto)
            }
        }
        val settings = root.optJSONObject("userSettings")?.let { JsonCodec.settingsFromJson(it.toString()) }
        val prefsObj = root.optJSONObject("portablePrefs")
        val portablePrefs = prefsObj?.let { p ->
            PortablePrefsSnapshot(
                forwardingEnabled = if (p.has("forwardingEnabled")) p.optBoolean("forwardingEnabled") else null,
                forwardingUpstream = p.optString("forwardingUpstream").takeIf { it.isNotBlank() },
                socks5Host = p.optString("socks5Host").takeIf { it.isNotBlank() },
                socks5Port = if (p.has("socks5Port")) p.optInt("socks5Port") else null,
                socks5Udp = if (p.has("socks5Udp")) p.optBoolean("socks5Udp") else null,
                socks5BypassPackage = p.optString("socks5BypassPackage").takeIf { it.isNotBlank() },
                forwardingDirectPackages = p.optJSONArray("forwardingDirectPackages")?.toStringList()?.toSet(),
                dnsBypassProtection = if (p.has("dnsBypassProtection")) p.optBoolean("dnsBypassProtection") else null,
                exitDnsProvider = p.optString("exitDnsProvider").takeIf { it.isNotBlank() },
                exitDnsCustomUrl = p.optString("exitDnsCustomUrl").takeIf { it.isNotBlank() },
                exitDnsFallback = if (p.has("exitDnsFallback")) p.optBoolean("exitDnsFallback") else null,
                blockOnLockScreen = if (p.has("blockOnLockScreen")) p.optBoolean("blockOnLockScreen") else null,
                lockScreenWhitelist = p.optJSONArray("lockScreenWhitelist")?.toStringList()?.toSet(),
                cloneAppsInheritPrimary = if (p.has("cloneAppsInheritPrimary")) p.optBoolean("cloneAppsInheritPrimary") else null,
                focusSchedulesJson = p.optString("focusSchedulesJson").takeIf { it.isNotBlank() },
                autoHideBottomBar = if (p.has("autoHideBottomBar")) p.optBoolean("autoHideBottomBar") else null,
                offlineMode = if (p.has("offlineMode")) p.optBoolean("offlineMode") else null,
                foregroundOnlyPackages = p.optJSONArray("foregroundOnlyPackages")?.toStringList()?.toSet(),
                forwardingSplitExitEnabled = if (p.has("forwardingSplitExitEnabled")) p.optBoolean("forwardingSplitExitEnabled") else null,
                forwardingProxyPackages = p.optJSONArray("forwardingProxyPackages")?.toStringList()?.toSet(),
            )
        }
        val autoUrls = root.optJSONArray("automationSubscriptionUrls")?.toStringList().orEmpty()
        return FullBackupSnapshot(
            format = format,
            version = version,
            rulesJson = wrappedRulesJson,
            profiles = profiles,
            activeProfileId = root.optString("activeProfileId").takeIf { it.isNotBlank() },
            userSettings = settings,
            portablePrefs = portablePrefs,
            automationSubscriptionUrls = autoUrls,
        )
    }
}
