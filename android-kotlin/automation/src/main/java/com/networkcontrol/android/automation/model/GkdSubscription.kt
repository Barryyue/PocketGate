package com.networkcontrol.android.automation.model

/** GKD 訂閱中的相對座標位置（用於 position 偏移點擊）。 */
data class RawPosition(
    val left: String? = null,
    val top: String? = null,
    val right: String? = null,
    val bottom: String? = null,
)

/** 單條匹配規則（與 GKD 社群訂閱格式相容）。 */
data class RawRule(
    val key: Int? = null,
    val name: String? = null,
    val preKeys: List<Int> = emptyList(),
    val matches: List<String> = emptyList(),
    val excludeMatches: List<String> = emptyList(),
    val anyMatches: List<String> = emptyList(),
    val activityIds: List<String> = emptyList(),
    val excludeActivityIds: List<String> = emptyList(),
    val fastQuery: Boolean? = null,
    val matchRoot: Boolean? = null,
    val matchDelay: Long? = null,
    val matchTime: Long? = null,
    val actionDelay: Long? = null,
    val actionCd: Long? = null,
    val actionMaximum: Int? = null,
    val resetMatch: String? = null,
    val action: String? = null,
    val position: RawPosition? = null,
)

/** 單個應用的規則組。 */
data class RawAppGroup(
    val key: Int,
    val name: String,
    val desc: String? = null,
    val enable: Boolean? = null,
    val activityIds: List<String> = emptyList(),
    val excludeActivityIds: List<String> = emptyList(),
    val fastQuery: Boolean? = null,
    val matchRoot: Boolean? = null,
    val matchDelay: Long? = null,
    val matchTime: Long? = null,
    val actionDelay: Long? = null,
    val actionCd: Long? = null,
    val actionMaximum: Int? = null,
    val resetMatch: String? = null,
    val action: String? = null,
    val rules: List<RawRule> = emptyList(),
)

/** 應用規則集合。 */
data class RawApp(
    val id: String,
    val name: String? = null,
    val groups: List<RawAppGroup> = emptyList(),
)

/** 全域規則對特定應用的覆寫條目。 */
data class RawGlobalApp(
    val id: String,
    val enable: Boolean? = null,
    val activityIds: List<String> = emptyList(),
    val excludeActivityIds: List<String> = emptyList(),
)

/** 全域規則組（對多數應用生效，例如通用開屏跳過）。 */
data class RawGlobalGroup(
    val key: Int,
    val name: String,
    val desc: String? = null,
    val enable: Boolean? = null,
    val matchLauncher: Boolean = false,
    val matchSystemApp: Boolean = false,
    val apps: List<RawGlobalApp> = emptyList(),
    val fastQuery: Boolean? = null,
    val matchRoot: Boolean? = null,
    val matchDelay: Long? = null,
    val matchTime: Long? = null,
    val actionDelay: Long? = null,
    val actionCd: Long? = null,
    val actionMaximum: Int? = null,
    val resetMatch: String? = null,
    val action: String? = null,
    val rules: List<RawRule> = emptyList(),
)

/** 訂閱分類（例如「開屏廣告」「青少年模式」）。 */
data class RawCategory(
    val key: Int,
    val name: String,
    val enable: Boolean? = null,
)

/** 解析後的完整 GKD 訂閱。 */
data class RawSubscription(
    val id: Long,
    val name: String,
    val version: Int,
    val author: String? = null,
    val checkUpdateUrl: String? = null,
    val categories: List<RawCategory> = emptyList(),
    val globalGroups: List<RawGlobalGroup> = emptyList(),
    val apps: List<RawApp> = emptyList(),
) {
    val totalGroupCount: Int
        get() = globalGroups.size + apps.sumOf { it.groups.size }
}

/** 訂閱解析結果（包含單條規則容錯跳過計數）。 */
data class ParsedSubscription(
    val subscription: RawSubscription,
    val skippedRuleCount: Int,
)

/** 保存在 automation.db 的訂閱記錄元資訊。 */
data class SubscriptionRecord(
    val id: Long,
    val name: String,
    val author: String?,
    val version: Int,
    val updateUrl: String?,
    val checkUpdateUrl: String?,
    val enabled: Boolean,
    val appCount: Int,
    val groupCount: Int,
    val skippedRuleCount: Int,
    val updatedAtMs: Long,
)

/** 使用者對單個規則組（含全域規則組）的開關覆寫。 */
data class GroupOverride(
    val subscriptionId: Long,
    /** 空字串表示全域規則組；非空為目標應用包名。 */
    val appId: String,
    val groupKey: Int,
    val enabled: Boolean,
)

/** 規則觸發紀錄。 */
data class TriggerLogEntry(
    val id: Long,
    val timestampMs: Long,
    val subscriptionId: Long,
    val subscriptionName: String,
    val appId: String,
    val activityId: String?,
    val groupKey: Int,
    val groupName: String,
    val ruleKey: Int?,
    val action: String,
)
