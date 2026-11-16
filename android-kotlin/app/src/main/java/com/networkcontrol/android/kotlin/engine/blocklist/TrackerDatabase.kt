package com.networkcontrol.android.kotlin.engine.blocklist

import org.json.JSONArray
import org.json.JSONObject

/** 追蹤器歸屬：哪家公司、什麼類別（類別已正規化，見 [TrackerCategory]）。 */
data class TrackerInfo(val company: String, val category: String)

/** 正規化後的類別；指紋與郵件兩種細分合併。 */
object TrackerCategory {
    const val ADVERTISING = "Advertising"
    const val ANALYTICS = "Analytics"
    const val SOCIAL = "Social"
    const val FINGERPRINTING = "Fingerprinting"
    const val CRYPTOMINING = "Cryptomining"
    const val EMAIL = "Email"
    const val CONTENT = "Content"
    const val ANTI_FRAUD = "Anti-fraud"
    const val CONSENT = "ConsentManagers"

    /** 可以整類攔截的類別（內容、反詐騙、同意管理通常是網站正常功能的一部分，不提供整類攔截）。 */
    val blockable = listOf(ADVERTISING, ANALYTICS, SOCIAL, FINGERPRINTING, CRYPTOMINING, EMAIL)

    /** 同一域名出現在多個類別時，取較「嚴重」的。 */
    private val priority = listOf(CRYPTOMINING, FINGERPRINTING, ADVERTISING, ANALYTICS, SOCIAL, EMAIL, CONTENT, ANTI_FRAUD, CONSENT)

    fun normalize(raw: String): String = when (raw) {
        "FingerprintingInvasive", "FingerprintingGeneral" -> FINGERPRINTING
        "EmailAggressive" -> EMAIL
        else -> raw
    }

    fun rank(category: String): Int = priority.indexOf(category).let { if (it < 0) priority.size else it }
}

/**
 * Disconnect 追蹤器資料庫（`services.json`）的查詢表。
 * 資料以 CC BY-NC-SA 4.0 授權，由使用者的裝置直接向 Disconnect 下載，不隨安裝包散佈。
 */
class TrackerDatabase(private val byDomain: Map<String, TrackerInfo>) {
    val size: Int get() = byDomain.size
    val companies: Int by lazy { byDomain.values.map { it.company }.toSet().size }

    /** 逐層去掉最左邊的標籤查詢（`ads.g.doubleclick.net` → `g.doubleclick.net` → `doubleclick.net`）。 */
    fun lookup(host: String?): TrackerInfo? {
        val name = host?.let(DomainHash::normalize) ?: return null
        var start = 0
        while (true) {
            byDomain[if (start == 0) name else name.substring(start)]?.let { return it }
            val dot = name.indexOf('.', start)
            if (dot < 0) return null
            start = dot + 1
            if (name.indexOf('.', start) < 0) return null
        }
    }

    companion object {
        fun parse(text: String): TrackerDatabase {
            val root = JSONObject(text)
            val categories = root.getJSONObject("categories")
            val result = HashMap<String, TrackerInfo>(16_384)
            for (rawCategory in categories.keys()) {
                val category = TrackerCategory.normalize(rawCategory)
                val entries = categories.optJSONArray(rawCategory) ?: continue
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    for (company in entry.keys()) {
                        val sites = entry.optJSONObject(company) ?: continue
                        for (site in sites.keys()) {
                            val domains = sites.opt(site) as? JSONArray ?: continue
                            for (j in 0 until domains.length()) {
                                val domain = DomainHash.normalize(domains.optString(j)) ?: continue
                                val existing = result[domain]
                                if (existing == null || TrackerCategory.rank(category) < TrackerCategory.rank(existing.category)) {
                                    result[domain] = TrackerInfo(company, category)
                                }
                            }
                        }
                    }
                }
            }
            return TrackerDatabase(result)
        }
    }
}
