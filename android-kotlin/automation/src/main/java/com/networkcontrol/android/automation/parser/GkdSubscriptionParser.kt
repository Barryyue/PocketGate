package com.networkcontrol.android.automation.parser

import com.networkcontrol.android.automation.model.ParsedSubscription
import com.networkcontrol.android.automation.model.RawApp
import com.networkcontrol.android.automation.model.RawAppGroup
import com.networkcontrol.android.automation.model.RawCategory
import com.networkcontrol.android.automation.model.RawGlobalApp
import com.networkcontrol.android.automation.model.RawGlobalGroup
import com.networkcontrol.android.automation.model.RawPosition
import com.networkcontrol.android.automation.model.RawRule
import com.networkcontrol.android.automation.model.RawSubscription
import li.gkd.selector.Selector
import li.gkd.selector.SelectorCompileResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析 GKD 社群訂閱 JSON5 並進行單條規則容錯校驗。
 *
 * 策略：
 * - 未知欄位靜默忽略，保持對上游新版訂閱的向前相容。
 * - 單條規則中任何選擇器無法通過 `gkd-selector` 編譯時，僅跳過該條規則並累加
 *   `skippedRuleCount`，不影響整份訂閱其餘規則的載入。
 */
object GkdSubscriptionParser {

    /** 解析版本檢查端點（`checkUpdateUrl`）的回應，回傳 version 整數（找不到則 null）。 */
    fun parseVersionInfo(rawText: String): Int? = runCatching {
        val obj = JSONObject(Json5Preprocessor.toStandardJson(rawText))
        if (obj.has("version")) obj.getInt("version") else null
    }.getOrNull()

    /** 解析完整訂閱 JSON5。 */
    fun parse(rawText: String, validateSelectors: Boolean = true): ParsedSubscription {
        val json = JSONObject(Json5Preprocessor.toStandardJson(rawText))
        val id = json.optLong("id", 0L)
        val name = json.optString("name", "").ifBlank { "Subscription #$id" }
        val version = json.optInt("version", 0)
        val author = json.optNullableString("author")
        val checkUpdateUrl = json.optNullableString("checkUpdateUrl")

        var skipped = 0

        val categories = parseCategories(json.optJSONArray("categories"))
        val globalGroups = parseGlobalGroups(json.optJSONArray("globalGroups"), validateSelectors) {
            skipped += it
        }
        val apps = parseApps(json.optJSONArray("apps"), validateSelectors) {
            skipped += it
        }

        val sub = RawSubscription(
            id = id,
            name = name,
            version = version,
            author = author,
            checkUpdateUrl = checkUpdateUrl,
            categories = categories,
            globalGroups = globalGroups,
            apps = apps,
        )
        return ParsedSubscription(subscription = sub, skippedRuleCount = skipped)
    }

    private fun parseCategories(arr: JSONArray?): List<RawCategory> {
        if (arr == null) return emptyList()
        val result = ArrayList<RawCategory>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val key = obj.optInt("key", i)
            val name = obj.optString("name", "").ifBlank { continue }
            result.add(
                RawCategory(
                    key = key,
                    name = name,
                    enable = obj.optNullableBoolean("enable"),
                ),
            )
        }
        return result
    }

    private fun parseGlobalGroups(
        arr: JSONArray?,
        validateSelectors: Boolean,
        onSkipped: (Int) -> Unit,
    ): List<RawGlobalGroup> {
        if (arr == null) return emptyList()
        val result = ArrayList<RawGlobalGroup>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val key = obj.optInt("key", i)
            val name = obj.optString("name", "").ifBlank { "Global #$key" }
            val (rules, skipped) = parseRules(obj.opt("rules"), validateSelectors)
            onSkipped(skipped)
            if (rules.isEmpty()) continue
            val apps = parseGlobalApps(obj.optJSONArray("apps"))
            result.add(
                RawGlobalGroup(
                    key = key,
                    name = name,
                    desc = obj.optNullableString("desc"),
                    enable = obj.optNullableBoolean("enable"),
                    matchLauncher = obj.optBoolean("matchLauncher", false),
                    matchSystemApp = obj.optBoolean("matchSystemApp", false),
                    apps = apps,
                    fastQuery = obj.optFastQuery(),
                    matchRoot = obj.optNullableBoolean("matchRoot"),
                    matchDelay = obj.optNullableLong("matchDelay"),
                    matchTime = obj.optNullableLong("matchTime"),
                    actionDelay = obj.optNullableLong("actionDelay"),
                    actionCd = obj.optNullableLong("actionCd"),
                    actionMaximum = obj.optNullableInt("actionMaximum"),
                    resetMatch = obj.optNullableString("resetMatch"),
                    action = obj.optNullableString("action"),
                    rules = rules,
                ),
            )
        }
        return result
    }

    private fun parseGlobalApps(arr: JSONArray?): List<RawGlobalApp> {
        if (arr == null) return emptyList()
        val result = ArrayList<RawGlobalApp>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").ifBlank { continue }
            result.add(
                RawGlobalApp(
                    id = id,
                    enable = obj.optNullableBoolean("enable"),
                    activityIds = parseStringOrList(obj.opt("activityIds")),
                    excludeActivityIds = parseStringOrList(obj.opt("excludeActivityIds")),
                ),
            )
        }
        return result
    }

    private fun parseApps(
        arr: JSONArray?,
        validateSelectors: Boolean,
        onSkipped: (Int) -> Unit,
    ): List<RawApp> {
        if (arr == null) return emptyList()
        val result = ArrayList<RawApp>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").ifBlank { continue }
            val groups = parseAppGroups(obj.optJSONArray("groups"), validateSelectors, onSkipped)
            if (groups.isEmpty()) continue
            result.add(
                RawApp(
                    id = id,
                    name = obj.optNullableString("name"),
                    groups = groups,
                ),
            )
        }
        return result
    }

    private fun parseAppGroups(
        arr: JSONArray?,
        validateSelectors: Boolean,
        onSkipped: (Int) -> Unit,
    ): List<RawAppGroup> {
        if (arr == null) return emptyList()
        val result = ArrayList<RawAppGroup>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val key = obj.optInt("key", i)
            val name = obj.optString("name", "").ifBlank { "Group #$key" }
            val (rules, skipped) = parseRules(obj.opt("rules"), validateSelectors)
            onSkipped(skipped)
            if (rules.isEmpty()) continue
            result.add(
                RawAppGroup(
                    key = key,
                    name = name,
                    desc = obj.optNullableString("desc"),
                    enable = obj.optNullableBoolean("enable"),
                    activityIds = parseStringOrList(obj.opt("activityIds")),
                    excludeActivityIds = parseStringOrList(obj.opt("excludeActivityIds")),
                    fastQuery = obj.optFastQuery(),
                    matchRoot = obj.optNullableBoolean("matchRoot"),
                    matchDelay = obj.optNullableLong("matchDelay"),
                    matchTime = obj.optNullableLong("matchTime"),
                    actionDelay = obj.optNullableLong("actionDelay"),
                    actionCd = obj.optNullableLong("actionCd"),
                    actionMaximum = obj.optNullableInt("actionMaximum"),
                    resetMatch = obj.optNullableString("resetMatch"),
                    action = obj.optNullableString("action"),
                    rules = rules,
                ),
            )
        }
        return result
    }

    private fun parseRules(raw: Any?, validateSelectors: Boolean): Pair<List<RawRule>, Int> {
        if (raw == null || raw === JSONObject.NULL) return emptyList<RawRule>() to 0
        val list = mutableListOf<RawRule>()
        var skipped = 0

        fun handleItem(item: Any?) {
            val rule = when (item) {
                is String -> RawRule(matches = listOf(item))
                is JSONObject -> parseRuleObject(item)
                else -> null
            } ?: return

            if (rule.matches.isEmpty() && rule.anyMatches.isEmpty()) {
                skipped++
                return
            }
            if (validateSelectors && !areSelectorsValid(rule)) {
                skipped++
                return
            }
            list.add(rule)
        }

        when (raw) {
            is JSONArray -> for (i in 0 until raw.length()) handleItem(raw.opt(i))
            else -> handleItem(raw)
        }
        return list to skipped
    }

    private fun parseRuleObject(obj: JSONObject): RawRule {
        val posObj = obj.optJSONObject("position")
        val position = posObj?.let {
            RawPosition(
                left = it.optPositionValue("left"),
                top = it.optPositionValue("top"),
                right = it.optPositionValue("right"),
                bottom = it.optPositionValue("bottom"),
            )
        }
        return RawRule(
            key = obj.optNullableInt("key"),
            name = obj.optNullableString("name"),
            preKeys = parseIntOrList(obj.opt("preKeys")),
            matches = parseStringOrList(obj.opt("matches")),
            excludeMatches = parseStringOrList(obj.opt("excludeMatches")),
            anyMatches = parseStringOrList(obj.opt("anyMatches")),
            activityIds = parseStringOrList(obj.opt("activityIds")),
            excludeActivityIds = parseStringOrList(obj.opt("excludeActivityIds")),
            fastQuery = obj.optFastQuery(),
            matchRoot = obj.optNullableBoolean("matchRoot"),
            matchDelay = obj.optNullableLong("matchDelay"),
            matchTime = obj.optNullableLong("matchTime"),
            actionDelay = obj.optNullableLong("actionDelay"),
            actionCd = obj.optNullableLong("actionCd"),
            actionMaximum = obj.optNullableInt("actionMaximum"),
            resetMatch = obj.optNullableString("resetMatch"),
            action = obj.optNullableString("action"),
            position = position,
        )
    }

    private fun areSelectorsValid(rule: RawRule): Boolean {
        for (src in rule.matches) {
            if (Selector.compile(src) is SelectorCompileResult.Failure) return false
        }
        for (src in rule.excludeMatches) {
            if (Selector.compile(src) is SelectorCompileResult.Failure) return false
        }
        for (src in rule.anyMatches) {
            if (Selector.compile(src) is SelectorCompileResult.Failure) return false
        }
        return true
    }

    private fun parseStringOrList(raw: Any?): List<String> = when (raw) {
        is String -> if (raw.isNotEmpty()) listOf(raw) else emptyList()
        is JSONArray -> buildList(raw.length()) {
            for (i in 0 until raw.length()) {
                val s = raw.optString(i, "")
                if (s.isNotEmpty()) add(s)
            }
        }
        else -> emptyList()
    }

    private fun parseIntOrList(raw: Any?): List<Int> = when (raw) {
        is Number -> listOf(raw.toInt())
        is JSONArray -> buildList(raw.length()) {
            for (i in 0 until raw.length()) {
                val item = raw.opt(i)
                if (item is Number) add(item.toInt())
            }
        }
        else -> emptyList()
    }

    private fun JSONObject.optFastQuery(): Boolean? =
        optNullableBoolean("fastQuery") ?: optNullableBoolean("quickFind")

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.optPositionValue(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return opt(key)?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
}
