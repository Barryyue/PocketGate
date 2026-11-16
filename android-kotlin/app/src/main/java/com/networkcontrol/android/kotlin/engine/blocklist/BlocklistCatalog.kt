package com.networkcontrol.android.kotlin.engine.blocklist

import com.networkcontrol.android.kotlin.core.release.NoteText

/** 內建的訂閱來源。名單由作者託管，本應用只在使用者的裝置上直接下載，不轉散佈。 */
data class BuiltinBlocklist(
    val id: String,
    val name: String,
    val description: NoteText,
    /** 依序嘗試：主地址失敗（例如在中國大陸連不上 GitHub）時改用鏡像。 */
    val urls: List<String>,
    val homepage: String,
    /** 第一次開啟攔截列表時預設勾選。 */
    val defaultEnabled: Boolean = false,
)

object BlocklistCatalog {
    val builtin: List<BuiltinBlocklist> = listOf(
        BuiltinBlocklist(
            id = "anti-ad",
            name = "anti-AD",
            description = NoteText(
                "Ads and trackers, tuned for Chinese apps and websites.",
                "广告与追踪，针对中文应用和网站。",
                "廣告與追蹤，針對中文應用程式和網站。",
            ),
            urls = listOf(
                "https://anti-ad.net/easylist.txt",
                "https://cdn.jsdelivr.net/gh/privacy-protection-tools/anti-AD@master/anti-ad-easylist.txt",
                "https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/anti-ad-easylist.txt",
            ),
            homepage = "https://github.com/privacy-protection-tools/anti-AD",
            defaultEnabled = true,
        ),
        BuiltinBlocklist(
            id = "hagezi-multi",
            name = "HaGeZi Multi NORMAL",
            description = NoteText(
                "All-round protection against ads, tracking and malware, with few false positives.",
                "全面防护：广告、追踪、恶意网站，误拦少。",
                "全面防護：廣告、追蹤、惡意網站，誤攔少。",
            ),
            urls = listOf(
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/multi.txt",
                "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@main/adblock/multi.txt",
            ),
            homepage = "https://github.com/hagezi/dns-blocklists",
            defaultEnabled = true,
        ),
        BuiltinBlocklist(
            id = "hagezi-pro",
            name = "HaGeZi Multi PRO",
            description = NoteText(
                "Stricter than NORMAL: blocks more trackers, occasionally breaks some features.",
                "比 NORMAL 更严格：拦更多追踪，偶尔会影响部分功能。",
                "比 NORMAL 更嚴格：攔更多追蹤，偶爾會影響部分功能。",
            ),
            urls = listOf(
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt",
                "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@main/adblock/pro.txt",
            ),
            homepage = "https://github.com/hagezi/dns-blocklists",
        ),
        BuiltinBlocklist(
            id = "hagezi-tif-mini",
            name = "HaGeZi Threat Intelligence (mini)",
            description = NoteText(
                "Malware, phishing, scam and crypto-jacking domains.",
                "恶意软件、钓鱼、诈骗和挖矿域名。",
                "惡意軟體、釣魚、詐騙和挖礦網域。",
            ),
            urls = listOf(
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/tif.mini.txt",
                "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@main/adblock/tif.mini.txt",
            ),
            homepage = "https://github.com/hagezi/dns-blocklists",
        ),
        BuiltinBlocklist(
            id = "oisd-small",
            name = "OISD small",
            description = NoteText(
                "Ads, trackers and malware with a focus on avoiding false positives.",
                "广告、追踪和恶意网站，以不误拦为优先。",
                "廣告、追蹤和惡意網站，以不誤攔為優先。",
            ),
            urls = listOf("https://small.oisd.nl/"),
            homepage = "https://oisd.nl",
        ),
        BuiltinBlocklist(
            id = "adguard-dns",
            name = "AdGuard DNS filter",
            description = NoteText(
                "The filter used by AdGuard DNS: ads and trackers.",
                "AdGuard DNS 使用的过滤器：广告与追踪。",
                "AdGuard DNS 使用的過濾器：廣告與追蹤。",
            ),
            urls = listOf("https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"),
            homepage = "https://github.com/AdguardTeam/AdGuardSDNSFilter",
        ),
        BuiltinBlocklist(
            id = "stevenblack",
            name = "StevenBlack hosts",
            description = NoteText(
                "Classic hosts file: ads and malware (exact domains only).",
                "经典 hosts：广告与恶意网站（只拦截完全相同的域名）。",
                "經典 hosts：廣告與惡意網站（只攔截完全相同的網域）。",
            ),
            urls = listOf(
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                "https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/hosts",
            ),
            homepage = "https://github.com/StevenBlack/hosts",
        ),
    )

    fun find(id: String): BuiltinBlocklist? = builtin.firstOrNull { it.id == id }
}
