package com.networkcontrol.android.kotlin.engine.vpn

/**
 * 計算 VPN 路由表：開啟「允許局域網 (LAN)」時，從全域路由扣除常見私有網段，
 * 同時明確保留本應用假 DNS / 虛擬網卡（10.111.222.0/24、198.18.0.0/15）與 hev mapdns（100.64.0.0/10）。
 * 採用純 CIDR 區間差集演算法，Android 10～16 全版本相容。
 */
object LanRouteCalculator {
    private val IPV4_LAN_EXCLUDES = listOf(
        // 10.0.0.0/8 但保留 10.111.222.0/24 供本機 VPN 介面與映射 DNS 使用
        range("10.0.0.0", "10.111.221.255"),
        range("10.111.223.0", "10.255.255.255"),
        range("169.254.0.0", "169.254.255.255"),
        range("172.16.0.0", "172.31.255.255"),
        range("192.168.0.0", "192.168.255.255"),
        range("224.0.0.0", "239.255.255.255"),
        range("255.255.255.255", "255.255.255.255"),
    ).sortedBy { it.first }

    fun ipv4Routes(allowLan: Boolean): List<Pair<String, Int>> {
        if (!allowLan) return listOf("0.0.0.0" to 0)
        val result = mutableListOf<Pair<String, Int>>()
        var cursor = 0L
        val maxIp = 0xFFFFFFFFL
        for ((exStart, exEnd) in IPV4_LAN_EXCLUDES) {
            if (cursor < exStart) {
                emitCidrs(cursor, exStart - 1L, result)
            }
            if (exEnd >= maxIp) {
                cursor = maxIp + 1L
                break
            }
            cursor = maxOf(cursor, exEnd + 1L)
        }
        if (cursor <= maxIp) {
            emitCidrs(cursor, maxIp, result)
        }
        return result
    }

    fun ipv6Routes(allowLan: Boolean): List<Pair<String, Int>> {
        if (!allowLan) return listOf("::" to 0)
        // 僅攔截公網 IPv6（2000::/3）與本應用隧道網段，自然排除 fc00::/7（ULA）與 fe80::/10（Link-local）。
        return listOf(
            "2000::" to 3,
            "fd00:6e63::" to 32,
        )
    }

    private fun emitCidrs(startIp: Long, endIp: Long, out: MutableList<Pair<String, Int>>) {
        var cur = startIp
        while (cur <= endIp) {
            val remaining = endIp - cur + 1L
            var maxBlock = if (cur == 0L) 1L shl 32 else (cur and -cur)
            while (maxBlock > remaining) {
                maxBlock = maxBlock ushr 1
            }
            val prefix = 32 - java.lang.Long.numberOfTrailingZeros(maxBlock)
            out += formatIpv4(cur) to prefix
            cur += maxBlock
        }
    }

    private fun range(start: String, end: String): Pair<Long, Long> = parseIpv4(start) to parseIpv4(end)

    private fun parseIpv4(ip: String): Long {
        val parts = ip.split('.')
        require(parts.size == 4) { "Invalid IPv4: $ip" }
        var acc = 0L
        for (p in parts) {
            acc = (acc shl 8) or (p.toLong() and 0xFFL)
        }
        return acc
    }

    private fun formatIpv4(value: Long): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
}
