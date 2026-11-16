package com.networkcontrol.android.kotlin.engine.vpn

/**
 * 把應用直接發往其他 DNS 伺服器（例如 Chrome 直連 8.8.8.8:53）的 IPv4 UDP 查詢改寫到本機映射 DNS，
 * 回覆時再把來源改回原本的伺服器，應用看起來就是原伺服器回答的。
 *
 * 這樣應用自己做的 DNS 也會拿到映射位址，之後的連線帶有域名，攔截列表與域名規則才能生效。
 *
 * 執行緒：[redirectQuery] 在虛擬網卡讀取執行緒、[restoreReply] 在 hev 側執行緒呼叫，以鎖保護 NAT 表。
 */
class DnsRedirect(
    private val mapDns: ByteArray = ForwardingDefaults.MapDnsAddress.address,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 4096,
) {
    private class Entry(val originalServer: Int, @Volatile var touchedAt: Long)

    private val table = object : LinkedHashMap<Long, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?) = size > maxEntries
    }

    /** 改寫一個 IPv4 UDP 查詢的目的位址；不是 IPv4 UDP 時回傳 false。 */
    fun redirectQuery(packet: ByteArray, length: Int): Boolean {
        val ihl = ipv4UdpHeaderLength(packet, length) ?: return false
        val client = readInt(packet, 12)
        val clientPort = readU16(packet, ihl)
        val transaction = readU16(packet, ihl + 8)
        val server = readInt(packet, 16)
        synchronized(table) { table[key(client, clientPort, transaction)] = Entry(server, clock()) }
        mapDns.copyInto(packet, 16)
        fixChecksums(packet, length, ihl)
        return true
    }

    /**
     * 若這是被改寫查詢的回覆，把來源改回原伺服器並回傳 true。
     *
     * 鍵必須包含 DNS 事務 ID：所有應用共用同一個虛擬網卡位址，只用「用戶端埠」當鍵時，
     * 系統解析器之後正好用到同一個埠、直接發給映射 DNS 的查詢，回覆會被誤改成來自別的伺服器，
     * 解析器丟棄這個回覆並在 3 秒後重送（實機上表現為並發解析時部分查詢多等 3 秒）。
     */
    fun restoreReply(packet: ByteArray, length: Int): Boolean {
        val ihl = ipv4UdpHeaderLength(packet, length) ?: return false
        val client = readInt(packet, 16)
        val clientPort = readU16(packet, ihl + 2)
        val transaction = readU16(packet, ihl + 8)
        val now = clock()
        // 一個查詢只有一個回覆：還原後立即移除，表也不會累積。
        val entry = synchronized(table) { table.remove(key(client, clientPort, transaction)) } ?: return false
        if (now - entry.touchedAt > ENTRY_TTL_MS) return false
        writeInt(packet, 12, entry.originalServer)
        fixChecksums(packet, length, ihl)
        return true
    }

    private fun key(address: Int, port: Int, transaction: Int): Long =
        ((address.toLong() and 0xFFFFFFFFL) shl 32) or (port.toLong() shl 16) or transaction.toLong()

    companion object {
        private const val ENTRY_TTL_MS = 60_000L

        private fun ipv4UdpHeaderLength(packet: ByteArray, length: Int): Int? {
            if (length < 28 || (packet[0].toInt() ushr 4) != 4) return null
            if ((packet[9].toInt() and 0xFF) != 17) return null
            if ((readU16(packet, 6) and 0x3FFF) != 0) return null // 分片
            val ihl = (packet[0].toInt() and 0x0F) * 4
            // 需要 UDP 標頭（8）+ DNS 事務 ID（2）
            return if (ihl >= 20 && ihl + 10 <= length) ihl else null
        }

        /** 重算 IPv4 標頭與 UDP 檢查碼（UDP 檢查碼為 0 表示未使用，保持 0）。 */
        internal fun fixChecksums(packet: ByteArray, length: Int, ihl: Int) {
            packet[10] = 0
            packet[11] = 0
            val ipSum = checksum(packet, 0, ihl, 0)
            packet[10] = (ipSum ushr 8).toByte()
            packet[11] = ipSum.toByte()

            val udpLength = readU16(packet, ihl + 4).coerceAtMost(length - ihl)
            val hadChecksum = packet[ihl + 6].toInt() != 0 || packet[ihl + 7].toInt() != 0
            if (!hadChecksum) return
            packet[ihl + 6] = 0
            packet[ihl + 7] = 0
            var pseudo = 0L
            for (i in 12 until 20 step 2) pseudo += readU16(packet, i)
            pseudo += 17
            pseudo += udpLength
            var sum = checksum(packet, ihl, udpLength, pseudo)
            if (sum == 0) sum = 0xFFFF
            packet[ihl + 6] = (sum ushr 8).toByte()
            packet[ihl + 7] = sum.toByte()
        }

        internal fun checksum(bytes: ByteArray, offset: Int, length: Int, initial: Long): Int {
            var sum = initial
            var i = offset
            val end = offset + length
            while (i + 1 < end) {
                sum += readU16(bytes, i)
                i += 2
            }
            if (i < end) sum += (bytes[i].toInt() and 0xFF) shl 8
            while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

        private fun readInt(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)

        private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
    }
}
