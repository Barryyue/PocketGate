package com.networkcontrol.android.kotlin.core.matching

import com.networkcontrol.android.kotlin.core.model.NetworkZone
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 可執行文件路徑模式：`*` 匹配單層中的任意字串，`**` 匹配任意層。
 * Android 的套件路徑（`android-app://...`）一律區分大小寫。
 */
class PathPattern private constructor(
    val pattern: String,
    private val ignoreCase: Boolean,
) {
    private val literal: String?
    private val regex: Regex?

    init {
        val normalized = normalize(pattern)
        if (!pattern.contains('*')) {
            literal = normalized
            regex = null
        } else {
            literal = null
            regex = Regex(
                toRegex(normalized),
                if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet(),
            )
        }
    }

    val literalPath: String? get() = literal

    fun matches(path: String): Boolean {
        val normalized = normalize(path)
        return if (literal != null) {
            if (ignoreCase) literal.equals(normalized, ignoreCase = true) else literal == normalized
        } else {
            regex!!.matches(normalized)
        }
    }

    companion object {
        val DefaultIgnoreCase: Boolean = false

        fun parse(pattern: String, ignoreCase: Boolean = DefaultIgnoreCase): PathPattern =
            PathPattern(pattern, ignoreCase)

        fun normalize(path: String): String = path.replace('\\', '/')

        private fun toRegex(pattern: String): String {
            val sb = StringBuilder("^")
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                if (c == '*') {
                    if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                        sb.append(".*")
                        i++
                    } else {
                        sb.append("[^/]*")
                    }
                } else {
                    sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return sb.append('$').toString()
        }
    }
}

object DomainName {
    fun normalize(name: String): String {
        val trimmed = name.trim().trimEnd('.')
        if (trimmed.isEmpty()) return trimmed
        return try {
            IDN.toASCII(trimmed).lowercase()
        } catch (_: IllegalArgumentException) {
            trimmed.lowercase()
        }
    }
}

/** 網域模式：`*.example.com` 匹配 example.com 的所有子網域，也匹配 example.com 本身。 */
class DomainPattern private constructor(
    val normalized: String,
    val isWildcard: Boolean,
) {
    private val suffix: String = if (isWildcard) normalized.substring(2) else normalized

    fun matches(normalizedDomain: String): Boolean {
        if (!isWildcard) return normalizedDomain == suffix
        if (normalizedDomain.length == suffix.length) return normalizedDomain == suffix
        return normalizedDomain.length > suffix.length &&
            normalizedDomain.endsWith(suffix) &&
            normalizedDomain[normalizedDomain.length - suffix.length - 1] == '.'
    }

    companion object {
        fun parse(pattern: String): DomainPattern {
            val trimmed = pattern.trim()
            return if (trimmed.startsWith("*.")) {
                DomainPattern("*." + DomainName.normalize(trimmed.substring(2)), true)
            } else {
                DomainPattern(DomainName.normalize(trimmed), false)
            }
        }
    }
}

/** IPv4 / IPv6 網段。 */
class IpNetwork private constructor(
    private val base: ByteArray,
    val prefixLength: Int,
) {
    val addressFamily: Int get() = base.size

    fun contains(address: InetAddress): Boolean {
        val bytes = normalizeAddress(address, base.size) ?: return false
        var remaining = prefixLength
        var index = 0
        while (remaining >= 8) {
            if (bytes[index] != base[index]) return false
            index++
            remaining -= 8
        }
        if (remaining > 0) {
            val mask = (0xFF shl (8 - remaining)) and 0xFF
            if ((bytes[index].toInt() and mask) != (base[index].toInt() and mask)) return false
        }
        return true
    }

    override fun toString(): String = "${InetAddress.getByAddress(base).hostAddress}/$prefixLength"

    companion object {
        fun parse(text: String): IpNetwork {
            val trimmed = text.trim()
            val slash = trimmed.indexOf('/')
            val addressPart = if (slash >= 0) trimmed.substring(0, slash) else trimmed
            val address = InetAddress.getByName(addressPart)
            val bytes = address.address
            val prefix = if (slash >= 0) {
                trimmed.substring(slash + 1).toInt()
            } else if (bytes.size == 4) 32 else 128
            require(prefix in 0..(bytes.size * 8)) { "網段前綴不合法：$text" }
            return IpNetwork(bytes.copyOf(), prefix)
        }

        fun single(address: InetAddress): IpNetwork =
            IpNetwork(address.address.copyOf(), if (address.address.size == 4) 32 else 128)

        private fun normalizeAddress(address: InetAddress, size: Int): ByteArray? {
            var bytes = address.address
            if (size == 4 && address is Inet6Address && bytes.size == 16) {
                val mapped = bytes.copyOfRange(0, 10).all { it.toInt() == 0 } &&
                    bytes[10].toInt() == 0xFF && bytes[11].toInt() == 0xFF
                if (mapped) {
                    bytes = bytes.copyOfRange(12, 16)
                }
            }
            return if (bytes.size == size) bytes else null
        }
    }
}

object NetworkZones {
    private val localNetworks: List<IpNetwork> = listOf(
        IpNetwork.parse("10.0.0.0/8"),
        IpNetwork.parse("172.16.0.0/12"),
        IpNetwork.parse("192.168.0.0/16"),
        IpNetwork.parse("169.254.0.0/16"),
        IpNetwork.parse("100.64.0.0/10"),
        IpNetwork.parse("224.0.0.0/4"),
        IpNetwork.parse("255.255.255.255/32"),
        IpNetwork.parse("fc00::/7"),
        IpNetwork.parse("fe80::/10"),
        IpNetwork.parse("ff00::/8"),
    )

    val localNetworkRanges: List<IpNetwork> get() = localNetworks

    fun classify(address: InetAddress): NetworkZone {
        val normalized = if (address is Inet6Address && address.isIpv4Mapped()) {
            InetAddress.getByAddress(address.address.copyOfRange(12, 16))
        } else {
            address
        }
        if (normalized.isLoopbackAddress) return NetworkZone.Loopback
        if (localNetworks.any { it.contains(normalized) }) return NetworkZone.LocalNetwork
        return NetworkZone.Internet
    }

    fun contains(network: IpNetwork, address: InetAddress): Boolean = network.contains(address)

    private fun Inet6Address.isIpv4Mapped(): Boolean {
        val bytes = address
        return bytes.size == 16 &&
            bytes.copyOfRange(0, 10).all { it.toInt() == 0 } &&
            bytes[10].toInt() == 0xFF && bytes[11].toInt() == 0xFF
    }
}

fun InetAddress.isIpv4(): Boolean = this is Inet4Address
