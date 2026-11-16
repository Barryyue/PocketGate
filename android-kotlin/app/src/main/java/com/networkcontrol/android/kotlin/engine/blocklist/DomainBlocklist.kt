package com.networkcontrol.android.kotlin.engine.blocklist

import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 一份已編譯的攔截名單。
 *
 * 記憶體：大型名單有數十萬個域名，用 `HashSet<String>` 會佔幾十 MB；這裡只存每個域名的 64 位元雜湊
 * （排序後二分搜尋），每個域名 8 bytes。對數十萬個項目而言，雜湊碰撞的機率可以忽略。
 *
 * 語意（與 AdGuard Home 等 DNS 攔截器一致）：
 * - `||example.com^`、`*.example.com`：攔截該域名與所有子域名（[suffix]）；
 * - hosts（`0.0.0.0 example.com`）與純域名：只攔截該域名本身（[exact]）；
 * - `@@||example.com^`：例外，該域名與子域名不攔截（[exceptions]）。
 */
class DomainBlocklist(
    val exact: LongArray,
    val suffix: LongArray,
    val exceptions: LongArray,
) {
    val size: Int get() = exact.size + suffix.size

    fun matches(host: String): Boolean {
        val name = DomainHash.normalize(host) ?: return false
        if (exceptions.isNotEmpty() && anySuffix(name, exceptions)) return false
        if (exact.isNotEmpty() && exact.binarySearch(DomainHash.of(name)) >= 0) return true
        return suffix.isNotEmpty() && anySuffix(name, suffix)
    }

    private fun anySuffix(name: String, table: LongArray): Boolean {
        var start = 0
        while (true) {
            if (table.binarySearch(DomainHash.of(name, start)) >= 0) return true
            val dot = name.indexOf('.', start)
            if (dot < 0) return false
            start = dot + 1
            // 不比對頂級域名本身（例如名單裡誤寫的 "com"）。
            if (name.indexOf('.', start) < 0) return false
        }
    }

    fun writeTo(file: File) {
        val temp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(temp.outputStream().buffered(64 * 1024)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            for (table in listOf(exact, suffix, exceptions)) {
                out.writeInt(table.size)
                table.forEach(out::writeLong)
            }
        }
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("cannot replace ${file.name}")
        }
    }

    companion object {
        private const val MAGIC = 0x4E43424C // "NCBL"
        private const val VERSION = 1
        val Empty = DomainBlocklist(LongArray(0), LongArray(0), LongArray(0))

        fun readFrom(file: File): DomainBlocklist =
            DataInputStream(file.inputStream().buffered(64 * 1024)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("bad blocklist file")
                val tables = Array(3) {
                    val count = input.readInt()
                    if (count < 0 || count > MAX_ENTRIES) throw IOException("bad blocklist size")
                    LongArray(count) { input.readLong() }
                }
                DomainBlocklist(tables[0], tables[1], tables[2])
            }

        /** 單份名單的上限；超過時多半不是名單（或是格式錯誤）。 */
        const val MAX_ENTRIES = 3_000_000
    }
}

/** 域名正規化與 64 位元 FNV-1a 雜湊（對 ASCII 小寫字串）。 */
object DomainHash {
    private const val OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L

    fun of(name: String, start: Int = 0): Long {
        var hash = OFFSET
        for (i in start until name.length) {
            hash = hash xor (name[i].code.toLong() and 0xFF)
            hash *= PRIME
        }
        return hash
    }

    /** 小寫、去掉結尾的點；不是合法主機名稱（含 IP 位址）時回傳 null。 */
    fun normalize(raw: String): String? {
        var name = raw.trim().lowercase()
        if (name.endsWith('.')) name = name.dropLast(1)
        if (name.isEmpty() || name.length > 253 || '.' !in name) return null
        var labelLength = 0
        var allDigitsOrDots = true
        for (c in name) {
            when {
                c == '.' -> {
                    if (labelLength == 0) return null
                    labelLength = 0
                }
                c in 'a'..'z' || c == '-' || c == '_' -> {
                    allDigitsOrDots = false
                    if (++labelLength > 63) return null
                }
                c in '0'..'9' -> if (++labelLength > 63) return null
                else -> return null
            }
        }
        if (labelLength == 0 || allDigitsOrDots) return null
        return name
    }
}

/**
 * 解析常見名單格式：hosts、純域名（一行一個）、AdBlock / AdGuard DNS 語法（`||domain^`、`@@||domain^`）。
 * 無法以域名表示的規則（正規表示式、路徑、`$dnsrewrite` 等修飾符）一律略過，並計入 [Result.skipped]。
 */
object BlocklistParser {
    class Result(val list: DomainBlocklist, val skipped: Int, val title: String?)

    private val sinkholes = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0", "::0")
    private val ignoredHosts = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback",
        "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0",
    )

    /** 允許的修飾符：不改變「整個域名攔截」語意的才接受。 */
    private val harmlessModifiers = setOf("important", "all", "third-party", "3p", "document", "doc", "popup")

    fun parse(input: InputStream, maxEntries: Int = DomainBlocklist.MAX_ENTRIES): Result =
        input.bufferedReader(Charsets.UTF_8).use { parse(it, maxEntries) }

    fun parse(reader: BufferedReader, maxEntries: Int = DomainBlocklist.MAX_ENTRIES): Result {
        val exact = LongBuilder()
        val suffix = LongBuilder()
        val exceptions = LongBuilder()
        var skipped = 0
        var title: String? = null
        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (line.isEmpty()) continue
            when (line[0]) {
                '!', '#', '[' -> {
                    if (title == null) title = titleOf(line)
                    continue
                }
            }
            val target: LongBuilder
            val name: String?
            when {
                line.startsWith("@@||") -> {
                    target = exceptions
                    name = adblockDomain(line.substring(4))
                }
                line.startsWith("||") -> {
                    target = suffix
                    name = adblockDomain(line.substring(2))
                }
                line.startsWith("*.") -> {
                    target = suffix
                    name = DomainHash.normalize(stripComment(line.substring(2)))
                }
                else -> {
                    target = exact
                    name = hostsOrPlain(line)
                }
            }
            if (name == null || name in ignoredHosts) {
                skipped++
                continue
            }
            target.add(DomainHash.of(name))
            if (exact.size + suffix.size > maxEntries) throw IOException("blocklist too large")
        }
        return Result(DomainBlocklist(exact.sorted(), suffix.sorted(), exceptions.sorted()), skipped, title)
    }

    private fun titleOf(line: String): String? {
        val body = line.trimStart('!', '#', ' ')
        return if (body.startsWith("Title:", ignoreCase = true)) body.substring(6).trim().takeIf { it.isNotEmpty() } else null
    }

    private fun stripComment(text: String): String {
        val hash = text.indexOf('#')
        return (if (hash >= 0) text.substring(0, hash) else text).trim()
    }

    /** `example.com^`、`example.com^$important`；其他形式回傳 null。 */
    private fun adblockDomain(body: String): String? {
        val caret = body.indexOf('^')
        val domainPart = if (caret >= 0) body.substring(0, caret) else body.substringBefore('$')
        val rest = if (caret >= 0) body.substring(caret + 1) else body.substring(domainPart.length)
        if (rest.isNotEmpty()) {
            if (!rest.startsWith('$')) return null
            val modifiers = rest.substring(1).split(',').map { it.trim().lowercase() }
            if (modifiers.any { it !in harmlessModifiers }) return null
        }
        if ('*' in domainPart || '/' in domainPart || ':' in domainPart) return null
        return DomainHash.normalize(domainPart)
    }

    private fun hostsOrPlain(line: String): String? {
        val content = stripComment(line)
        if (content.isEmpty()) return null
        val parts = content.split(' ', '\t').filter { it.isNotEmpty() }
        return when (parts.size) {
            1 -> DomainHash.normalize(parts[0])
            else -> if (parts[0] in sinkholes) DomainHash.normalize(parts[1]) else null
        }
    }

    private class LongBuilder {
        private var data = LongArray(1024)
        var size = 0
            private set

        fun add(value: Long) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun sorted(): LongArray {
            val result = data.copyOf(size)
            result.sort()
            // 去重
            if (result.isEmpty()) return result
            var unique = 1
            for (i in 1 until result.size) {
                if (result[i] != result[unique - 1]) result[unique++] = result[i]
            }
            return if (unique == result.size) result else result.copyOf(unique)
        }
    }
}

/**
 * 多份名單與追蹤器資料庫的聯合；不可變，更新時整個替換。
 * @param blockedCategories 要整類攔截的追蹤器類別（命中時回傳 `tracker:<類別>`）。
 */
class BlocklistMatcher(
    private val lists: List<Pair<String, DomainBlocklist>>,
    val trackers: TrackerDatabase? = null,
    private val blockedCategories: Set<String> = emptySet(),
) {
    /** 沒有任何攔截條件（追蹤器資料庫只用於識別時也算空）。 */
    val isEmpty: Boolean get() = lists.isEmpty() && (trackers == null || blockedCategories.isEmpty())
    val totalEntries: Int = lists.sumOf { it.second.size }

    /** 回傳命中的名單 id（追蹤器類別為 `tracker:<類別>`）；沒有命中時為 null。 */
    fun match(host: String?): String? {
        if (host.isNullOrEmpty() || isEmpty) return null
        for ((id, list) in lists) {
            if (list.matches(host)) return id
        }
        if (blockedCategories.isNotEmpty()) {
            val info = trackers?.lookup(host) ?: return null
            // 屬於任何一個被攔截的類別就攔截；記錄時優先用主要類別。
            val hit = if (info.category in blockedCategories) info.category else info.categories.firstOrNull { it in blockedCategories }
            if (hit != null) return TRACKER_PREFIX + hit
        }
        return null
    }

    /** 追蹤器歸屬（只用於顯示）。 */
    fun tracker(host: String?): TrackerInfo? = trackers?.lookup(host)

    /** 用於判斷是否需要通知引擎重新判決。 */
    internal val signature: String = lists.joinToString { "${it.first}:${it.second.size}" } +
        "|${trackers?.size ?: 0}|${blockedCategories.sorted()}"

    companion object {
        const val TRACKER_PREFIX = "tracker:"
        val Empty = BlocklistMatcher(emptyList())
    }
}
