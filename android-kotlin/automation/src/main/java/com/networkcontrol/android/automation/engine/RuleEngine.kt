package com.networkcontrol.android.automation.engine

import com.networkcontrol.android.automation.model.GroupOverride
import com.networkcontrol.android.automation.model.RawPosition
import com.networkcontrol.android.automation.model.RawRule
import com.networkcontrol.android.automation.model.RawSubscription
import li.gkd.selector.MatchOptions
import li.gkd.selector.NodeAdapter
import li.gkd.selector.Selector
import li.gkd.selector.SelectorCompileResult

/** 編譯後的單條可執行規則。 */
data class CompiledRule(
    val subscriptionId: Long,
    val subscriptionName: String,
    val appId: String,
    val isGlobal: Boolean,
    val groupKey: Int,
    val groupName: String,
    val ruleKey: Int?,
    val preKeys: Set<Int>,
    val matches: List<Selector>,
    val excludeMatches: List<Selector>,
    val anyMatches: List<Selector>,
    val activityIds: List<String>,
    val excludeActivityIds: List<String>,
    val fastQuery: Boolean,
    val matchRoot: Boolean,
    val matchDelayMs: Long,
    val matchTimeMs: Long?,
    val actionDelayMs: Long,
    val actionCdMs: Long,
    val actionMaximum: Int?,
    val resetMatch: String,
    val action: String,
    val position: RawPosition?,
) {
    /** 用於去重與狀態機鍵值。 */
    val groupUniqueKey: String = "$subscriptionId:${if (isGlobal) "g" else appId}:$groupKey"
    val ruleUniqueKey: String = "$groupUniqueKey:${ruleKey ?: hashCode()}"
}

/** 規則匹配成功結果。 */
data class MatchedRuleResult<T : Any>(
    val rule: CompiledRule,
    val targetNode: T,
)

/**
 * 純邏輯規則索引與匹配引擎（不依賴 Android API，可在 JVM 完整單元測試）。
 *
 * - 按 `appId` 建 O(1) 雜湊索引，無規則的應用直接秒返，零遍歷開銷。
 * - 維護每個規則組的 `preKeys`、`actionCd`、`actionMaximum`、`matchTime`、`matchDelay` 狀態。
 */
class RuleEngine(
    subscriptions: List<RawSubscription>,
    overrides: List<GroupOverride>,
) {
    private val appRules: Map<String, List<CompiledRule>>
    private val globalRules: List<GlobalRuleEntry>

    private var currentAppId: String? = null
    private var currentActivityId: String? = null
    private var appEnteredAtMs: Long = 0L
    private var activityEnteredAtMs: Long = 0L

    private val lastTriggerByRule = HashMap<String, Long>()
    private val triggerCountByGroup = HashMap<String, Int>()
    private val lastTriggeredKeyByGroup = HashMap<String, Int>()

    private data class GlobalRuleEntry(
        val compiled: CompiledRule,
        val appOverrides: Map<String, GlobalAppScope>,
    )

    private data class GlobalAppScope(
        val enable: Boolean?,
        val activityIds: List<String>,
        val excludeActivityIds: List<String>,
    )

    init {
        val overrideMap = overrides.associateBy { Triple(it.subscriptionId, it.appId, it.groupKey) }
        val appMap = HashMap<String, MutableList<CompiledRule>>()
        val globals = mutableListOf<GlobalRuleEntry>()

        for (sub in subscriptions) {
            val categoryEnable = sub.categories.associate { it.name to it.enable }

            // 編譯應用專屬規則
            for (app in sub.apps) {
                for (group in app.groups) {
                    val explicit = overrideMap[Triple(sub.id, app.id, group.key)]?.enabled
                    val catDefault = categoryEnable.entries.firstOrNull { (catName, _) ->
                        group.name.startsWith(catName)
                    }?.value
                    val enabled = explicit ?: group.enable ?: catDefault ?: true
                    if (!enabled) continue

                    for (rule in group.rules) {
                        val compiled = compileRule(
                            subId = sub.id,
                            subName = sub.name,
                            appId = app.id,
                            isGlobal = false,
                            groupKey = group.key,
                            groupName = group.name,
                            groupActivityIds = group.activityIds,
                            groupExcludeActivityIds = group.excludeActivityIds,
                            groupFastQuery = group.fastQuery,
                            groupMatchRoot = group.matchRoot,
                            groupMatchDelay = group.matchDelay,
                            groupMatchTime = group.matchTime,
                            groupActionDelay = group.actionDelay,
                            groupActionCd = group.actionCd,
                            groupActionMaximum = group.actionMaximum,
                            groupResetMatch = group.resetMatch,
                            groupAction = group.action,
                            raw = rule,
                        ) ?: continue
                        appMap.getOrPut(app.id) { mutableListOf() }.add(compiled)
                    }
                }
            }

            // 編譯全域規則
            for (group in sub.globalGroups) {
                val explicit = overrideMap[Triple(sub.id, "", group.key)]?.enabled
                val enabled = explicit ?: group.enable ?: true
                if (!enabled) continue

                val appScopes = group.apps.associate { ga ->
                    ga.id to GlobalAppScope(
                        enable = ga.enable,
                        activityIds = ga.activityIds,
                        excludeActivityIds = ga.excludeActivityIds,
                    )
                }

                for (rule in group.rules) {
                    val compiled = compileRule(
                        subId = sub.id,
                        subName = sub.name,
                        appId = "",
                        isGlobal = true,
                        groupKey = group.key,
                        groupName = group.name,
                        groupActivityIds = emptyList(),
                        groupExcludeActivityIds = emptyList(),
                        groupFastQuery = group.fastQuery,
                        groupMatchRoot = group.matchRoot,
                        groupMatchDelay = group.matchDelay,
                        groupMatchTime = group.matchTime,
                        groupActionDelay = group.actionDelay,
                        groupActionCd = group.actionCd,
                        groupActionMaximum = group.actionMaximum,
                        groupResetMatch = group.resetMatch,
                        groupAction = group.action,
                        raw = rule,
                    ) ?: continue
                    globals.add(GlobalRuleEntry(compiled = compiled, appOverrides = appScopes))
                }
            }
        }

        appRules = appMap
        globalRules = globals
    }

    /** 通知引擎前台應用或 Activity 變化，用於重置 `resetMatch` 狀態與計算 `matchTime`。 */
    fun onForegroundChanged(appId: String, activityId: String?, nowMs: Long) {
        if (appId != currentAppId) {
            currentAppId = appId
            currentActivityId = activityId
            appEnteredAtMs = nowMs
            activityEnteredAtMs = nowMs
            triggerCountByGroup.clear()
            lastTriggeredKeyByGroup.clear()
        } else if (activityId != null && activityId != currentActivityId) {
            currentActivityId = activityId
            activityEnteredAtMs = nowMs
            // 僅重置 resetMatch == "activity" 的規則組狀態
            val toReset = triggerCountByGroup.keys.filter { key ->
                // 簡化：activity 切換時清理非 app 級狀態
                true
            }
            for (k in toReset) {
                if (isActivityResetGroup(k)) {
                    triggerCountByGroup.remove(k)
                    lastTriggeredKeyByGroup.remove(k)
                }
            }
        }
    }

    /** 快速判斷目標應用是否有任何可能生效的規則（無規則時直接跳過 UI 樹查詢）。 */
    fun hasRulesForApp(appId: String): Boolean {
        if (!appRules[appId].isNullOrEmpty()) return true
        return globalRules.any { entry ->
            val scope = entry.appOverrides[appId]
            scope?.enable != false
        }
    }

    /** 針對目前畫面根節點執行匹配，回傳第一條命中的規則與目標節點。 */
    fun <T : Any> evaluate(
        appId: String,
        activityId: String?,
        rootNode: T,
        adapter: NodeAdapter<T>,
        nowMs: Long,
    ): MatchedRuleResult<T>? {
        if (currentAppId != appId) {
            onForegroundChanged(appId, activityId, nowMs)
        }
        val candidates = collectCandidates(appId, activityId)
        if (candidates.isEmpty()) return null

        for (rule in candidates) {
            if (!checkTimingAndState(rule, nowMs)) continue

            val options = MatchOptions(fastQuery = rule.fastQuery)

            // 1. 檢查 excludeMatches：任一排除選擇器命中即放棄此規則
            val excluded = rule.excludeMatches.any { sel ->
                matchSelector(rootNode, sel, adapter, options, rule.matchRoot) != null
            }
            if (excluded) continue

            // 2. 檢查 matches（全部必須命中，以最後一個選擇器的目標節點作為點擊對象）
            var target: T? = null
            if (rule.matches.isNotEmpty()) {
                var allMatched = true
                for (sel in rule.matches) {
                    val hit = matchSelector(rootNode, sel, adapter, options, rule.matchRoot)
                    if (hit == null) {
                        allMatched = false
                        break
                    }
                    target = hit
                }
                if (!allMatched) continue
            }

            // 3. 檢查 anyMatches（任一命中即可）
            if (rule.anyMatches.isNotEmpty()) {
                val anyHit = rule.anyMatches.firstNotNullOfOrNull { sel ->
                    matchSelector(rootNode, sel, adapter, options, rule.matchRoot)
                } ?: continue
                if (target == null) target = anyHit
            }

            if (target != null) {
                recordTrigger(rule, nowMs)
                return MatchedRuleResult(rule = rule, targetNode = target)
            }
        }
        return null
    }

    private fun <T : Any> matchSelector(
        rootNode: T,
        selector: Selector,
        adapter: NodeAdapter<T>,
        options: MatchOptions,
        matchRoot: Boolean,
    ): T? {
        if (matchRoot || selector.isMatchRoot) {
            selector.match(rootNode, adapter, options)?.let { return it }
        }
        return adapter.querySelector(rootNode, selector, options)
    }

    private fun collectCandidates(appId: String, activityId: String?): List<CompiledRule> {
        val list = mutableListOf<CompiledRule>()
        appRules[appId]?.forEach { rule ->
            if (matchesActivity(appId, activityId, rule.activityIds, rule.excludeActivityIds)) {
                list.add(rule)
            }
        }
        for (entry in globalRules) {
            val scope = entry.appOverrides[appId]
            if (scope?.enable == false) continue
            val actIds = if (!scope?.activityIds.isNullOrEmpty()) scope!!.activityIds else entry.compiled.activityIds
            val excIds = if (!scope?.excludeActivityIds.isNullOrEmpty()) scope!!.excludeActivityIds else entry.compiled.excludeActivityIds
            if (matchesActivity(appId, activityId, actIds, excIds)) {
                list.add(entry.compiled)
            }
        }
        return list
    }

    private fun matchesActivity(
        appId: String,
        activityId: String?,
        include: List<String>,
        exclude: List<String>,
    ): Boolean {
        if (exclude.isNotEmpty() && activityId != null) {
            if (exclude.any { matchesActivityPattern(appId, activityId, it) }) return false
        }
        if (include.isNotEmpty()) {
            if (activityId == null) return false
            if (include.none { matchesActivityPattern(appId, activityId, it) }) return false
        }
        return true
    }

    private fun matchesActivityPattern(appId: String, actual: String, pattern: String): Boolean {
        if (actual == pattern) return true
        if (pattern.startsWith(".") && actual == "$appId$pattern") return true
        return false
    }

    private fun checkTimingAndState(rule: CompiledRule, nowMs: Long): Boolean {
        // preKeys 檢查
        if (rule.preKeys.isNotEmpty()) {
            val lastKey = lastTriggeredKeyByGroup[rule.groupUniqueKey]
            if (lastKey == null || lastKey !in rule.preKeys) return false
        }
        // actionCd 冷卻時間
        val lastAt = lastTriggerByRule[rule.ruleUniqueKey]
        if (lastAt != null && nowMs - lastAt < rule.actionCdMs) return false

        // actionMaximum 次數上限
        val maxCount = rule.actionMaximum
        if (maxCount != null) {
            val count = triggerCountByGroup[rule.groupUniqueKey] ?: 0
            if (count >= maxCount) return false
        }

        // matchDelay / matchTime
        val baseTime = if (rule.resetMatch == "app") appEnteredAtMs else activityEnteredAtMs
        val elapsed = nowMs - baseTime
        if (rule.matchDelayMs > 0 && elapsed < rule.matchDelayMs) return false
        if (rule.matchTimeMs != null && elapsed > rule.matchTimeMs) return false

        return true
    }

    private fun recordTrigger(rule: CompiledRule, nowMs: Long) {
        lastTriggerByRule[rule.ruleUniqueKey] = nowMs
        triggerCountByGroup[rule.groupUniqueKey] = (triggerCountByGroup[rule.groupUniqueKey] ?: 0) + 1
        rule.ruleKey?.let { lastTriggeredKeyByGroup[rule.groupUniqueKey] = it }
    }

    private fun isActivityResetGroup(groupUniqueKey: String): Boolean {
        appRules.values.forEach { list ->
            val found = list.firstOrNull { it.groupUniqueKey == groupUniqueKey }
            if (found != null) return found.resetMatch != "app"
        }
        val globalFound = globalRules.firstOrNull { it.compiled.groupUniqueKey == groupUniqueKey }
        if (globalFound != null) return globalFound.compiled.resetMatch != "app"
        return true
    }

    private fun compileRule(
        subId: Long,
        subName: String,
        appId: String,
        isGlobal: Boolean,
        groupKey: Int,
        groupName: String,
        groupActivityIds: List<String>,
        groupExcludeActivityIds: List<String>,
        groupFastQuery: Boolean?,
        groupMatchRoot: Boolean?,
        groupMatchDelay: Long?,
        groupMatchTime: Long?,
        groupActionDelay: Long?,
        groupActionCd: Long?,
        groupActionMaximum: Int?,
        groupResetMatch: String?,
        groupAction: String?,
        raw: RawRule,
    ): CompiledRule? {
        val matches = compileSelectors(raw.matches) ?: return null
        val excludes = compileSelectors(raw.excludeMatches) ?: return null
        val anys = compileSelectors(raw.anyMatches) ?: return null
        if (matches.isEmpty() && anys.isEmpty()) return null

        return CompiledRule(
            subscriptionId = subId,
            subscriptionName = subName,
            appId = appId,
            isGlobal = isGlobal,
            groupKey = groupKey,
            groupName = groupName,
            ruleKey = raw.key,
            preKeys = raw.preKeys.toSet(),
            matches = matches,
            excludeMatches = excludes,
            anyMatches = anys,
            activityIds = raw.activityIds.ifEmpty { groupActivityIds },
            excludeActivityIds = raw.excludeActivityIds.ifEmpty { groupExcludeActivityIds },
            fastQuery = raw.fastQuery ?: groupFastQuery ?: false,
            matchRoot = raw.matchRoot ?: groupMatchRoot ?: false,
            matchDelayMs = raw.matchDelay ?: groupMatchDelay ?: 0L,
            matchTimeMs = raw.matchTime ?: groupMatchTime,
            actionDelayMs = raw.actionDelay ?: groupActionDelay ?: 0L,
            actionCdMs = raw.actionCd ?: groupActionCd ?: DEFAULT_ACTION_CD_MS,
            actionMaximum = raw.actionMaximum ?: groupActionMaximum,
            resetMatch = raw.resetMatch ?: groupResetMatch ?: "activity",
            action = raw.action ?: groupAction ?: "click",
            position = raw.position,
        )
    }

    private fun compileSelectors(sources: List<String>): List<Selector>? {
        if (sources.isEmpty()) return emptyList()
        val out = ArrayList<Selector>(sources.size)
        for (src in sources) {
            when (val res = Selector.compile(src)) {
                is SelectorCompileResult.Success -> out.add(res.value)
                is SelectorCompileResult.Failure -> return null
            }
        }
        return out
    }

    companion object {
        const val DEFAULT_ACTION_CD_MS = 1000L
    }
}
