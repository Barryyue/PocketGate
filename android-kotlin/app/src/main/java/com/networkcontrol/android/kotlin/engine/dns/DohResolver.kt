package com.networkcontrol.android.kotlin.engine.dns

import com.networkcontrol.android.kotlin.platform.AppLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException

/** 內建出口可選的加密 DNS（DoH）服務。地址一律用 IP，避免「解析 DoH 伺服器本身」的引導問題。 */
data class DohProvider(val id: String, val name: String, val url: String)

object DohProviders {
    const val SYSTEM = "system"

    val all: List<DohProvider> = listOf(
        DohProvider("cloudflare", "Cloudflare 1.1.1.1", "https://1.1.1.1/dns-query"),
        DohProvider("google", "Google 8.8.8.8", "https://8.8.8.8/dns-query"),
        DohProvider("quad9", "Quad9 9.9.9.9", "https://9.9.9.9/dns-query"),
        DohProvider("alidns", "AliDNS 223.5.5.5", "https://223.5.5.5/dns-query"),
        DohProvider("dnspod", "DNSPod 1.12.12.12", "https://1.12.12.12/dns-query"),
    )

    fun find(id: String): DohProvider? = all.firstOrNull { it.id == id }
}

/**
 * RFC 8484 DoH 用戶端（POST application/dns-message），供內建出口解析域名。
 *
 * - 結果按 TTL 快取（最短 30 秒、最長 1 小時），負面結果快取 30 秒；
 * - IPv4 優先：先查 A，沒有時再查 AAAA；
 * - 斷路器：連續失敗 3 次後 2 分鐘內直接使用 [fallback]（若允許），避免每條連線都等逾時；
 * - [fallback] 為 null 時（不允許退回明文 DNS）解析失敗就是失敗。
 */
class DohResolver(
    private val url: () -> String?,
    private val open: (URL) -> HttpURLConnection,
    private val fallback: () -> ((String) -> Array<InetAddress>)?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Int = 5_000,
) {
    private class Entry(val addresses: Array<InetAddress>, val expiresAt: Long)

    private val cache = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 2048
    }

    @Volatile
    private var failures = 0

    @Volatile
    private var openUntil = 0L

    fun resolve(host: String): Array<InetAddress> {
        val name = host.lowercase().trimEnd('.')
        val endpoint = url()
        if (endpoint == null) return systemOrFail(name, null)
        val now = clock()
        synchronized(cache) { cache[name] }?.takeIf { it.expiresAt > now }?.let { entry ->
            if (entry.addresses.isEmpty()) throw UnknownHostException(name)
            return entry.addresses
        }
        if (now < openUntil) return systemOrFail(name, IOException("encrypted DNS unavailable"))
        return try {
            var records = query(endpoint, name, DnsMessage.TYPE_A)
            if (records.isEmpty()) records = query(endpoint, name, DnsMessage.TYPE_AAAA)
            failures = 0
            val ttl = (records.minOfOrNull { it.ttlSeconds } ?: NEGATIVE_TTL_S).coerceIn(MIN_TTL_S, MAX_TTL_S)
            val addresses = records.map { it.address }.toTypedArray()
            synchronized(cache) { cache[name] = Entry(addresses, now + ttl * 1000) }
            if (addresses.isEmpty()) throw UnknownHostException(name)
            addresses
        } catch (ex: UnknownHostException) {
            throw ex
        } catch (ex: IOException) {
            if (++failures >= BREAKER_THRESHOLD) {
                openUntil = clock() + BREAKER_OPEN_MS
                failures = 0
                AppLog.w("dns", "encrypted DNS failing (${ex.message}); using fallback for ${BREAKER_OPEN_MS / 1000}s")
            }
            systemOrFail(name, ex)
        }
    }

    private fun systemOrFail(name: String, cause: IOException?): Array<InetAddress> {
        val system = fallback() ?: throw (cause ?: UnknownHostException(name))
        return system(name)
    }

    private fun query(endpoint: String, name: String, type: Int): List<DnsRecord> {
        val body = DnsMessage.buildQuery(name, type)
        val connection = open(URL(endpoint))
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", MEDIA_TYPE)
            connection.setRequestProperty("Accept", MEDIA_TYPE)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code != 200) throw IOException("DoH HTTP $code")
            val response = connection.inputStream.use { it.readBytes() }
            when (DnsMessage.rcode(response)) {
                0 -> Unit
                3 -> return emptyList() // NXDOMAIN
                else -> throw IOException("DoH rcode ${DnsMessage.rcode(response)}")
            }
            return DnsMessage.parseAnswers(response, type)
        } finally {
            connection.disconnect()
        }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
        failures = 0
        openUntil = 0
    }

    private companion object {
        const val MEDIA_TYPE = "application/dns-message"
        const val MIN_TTL_S = 30L
        const val MAX_TTL_S = 3600L
        const val NEGATIVE_TTL_S = 30L
        const val BREAKER_THRESHOLD = 3
        const val BREAKER_OPEN_MS = 120_000L
    }
}

/**
 * 防止應用繞過本機 DNS：知名的 DoH 服務網域與 IP。
 * 應用若自己用 DoH / DoT 解析，連線就不帶域名，攔截列表與域名規則都看不到它要去哪裡。
 */
object EncryptedDnsTargets {
    val hostnames: Set<String> = setOf(
        "dns.google", "dns.google.com", "8888.google",
        "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "one.one.one.one", "1dot1dot1dot1.cloudflare-dns.com",
        "chrome.cloudflare-dns.com", "security.cloudflare-dns.com", "family.cloudflare-dns.com",
        "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
        "doh.opendns.com", "dns.adguard-dns.com", "dns.adguard.com", "unfiltered.adguard-dns.com", "family.adguard-dns.com",
        "dns.nextdns.io", "doh.cleanbrowsing.org", "doh.mullvad.net", "dns.mullvad.net",
        "dns.alidns.com", "doh.pub", "sm2.doh.pub", "doh.360.cn", "dns.twnic.tw", "doh.dns.sb", "dns.sb",
    )

    val addresses: Set<String> = setOf(
        "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "149.112.112.112",
        "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15",
        "223.5.5.5", "223.6.6.6", "1.12.12.12", "120.53.53.53", "185.222.222.222", "45.11.45.11",
        "2606:4700:4700::1111", "2606:4700:4700::1001", "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2620:fe::fe", "2620:fe::9",
    )

    /** 是否為加密 DNS 連線：DoT / DoQ（853），或連到知名 DoH 服務（443）。 */
    fun matches(port: Int, address: InetAddress, hostname: String?): Boolean {
        if (port == 853) return true
        if (port != 443) return false
        if (hostname != null) {
            val name = hostname.lowercase().trimEnd('.')
            if (name in hostnames) return true
            // 子域名（例如 xxxx.dns.nextdns.io）
            var dot = name.indexOf('.')
            while (dot >= 0) {
                if (name.substring(dot + 1) in hostnames) return true
                dot = name.indexOf('.', dot + 1)
            }
        }
        return address in addressSet
    }

    /** 以 InetAddress 比較（Java 的 IPv6 文字表示不壓縮零，直接比字串會漏掉）。字面值不會觸發 DNS 查詢。 */
    private val addressSet: Set<InetAddress> by lazy { addresses.map { InetAddress.getByName(it) }.toSet() }
}
