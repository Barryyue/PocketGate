package com.networkcontrol.android.kotlin.engine.vpn

/**
 * 映射 DNS 查詢的放行與重送。
 *
 * 逐包追蹤發現 hev 的映射 DNS 會丟查詢（應用要等 Android 解析器 3～5 秒後重送，表現為「打開網頁時部分連線很慢」）：
 * 1. 同時收到多個查詢時只回覆第一個、其餘丟棄（同一毫秒內到達的 4 個查詢只有 1 個有回覆）；
 * 2. 剛送出回覆的同一毫秒內再收到查詢，也會丟棄；
 * 3. 即使間隔數毫秒，仍有約 4% 的查詢沒有回覆。
 *
 * 所以這裡一次只放一個查詢在途、回覆後間隔 [minGapMs] 再放行下一個，並且自己重送：
 * 在途查詢 [retryMs] 內沒有回覆就重送，最多 [maxAttempts] 次。hev 正常 1～5 ms 就回覆，排隊與重送的代價都很小。
 * 回覆以「用戶端埠 + DNS 事務 ID」對應到在途查詢。
 *
 * hev 重啟後的映射重放（見 [FakeIpMappings]）經 [enqueuePriority] 排在所有應用查詢之前；
 * 重放期間應用的查詢一律排隊，確保 hev 依原順序分配位址。
 *
 * 執行緒：[offer] 在虛擬網卡讀取執行緒、[onResponse] 在 hev 側執行緒、[drain] 在放行執行緒呼叫，以物件鎖同步。
 */
class DnsPacer(
    private val limit: Int = 1,
    private val retryMs: Long = 25,
    private val maxAttempts: Int = 3,
    /** 收到回覆後到放行下一個查詢之間的最短間隔。 */
    private val minGapMs: Long = 2,
    private val maxQueue: Int = 512,
    /** 查詢多次重送仍無回覆而放棄時呼叫（在鎖外呼叫）。 */
    private val onGiveUp: (ByteArray) -> Unit = {},
) {
    private class InFlight(val key: Long, val packet: ByteArray, var sentAt: Long, var attempts: Int)

    private val inFlight = ArrayList<InFlight>(limit)
    private val queue = ArrayDeque<ByteArray>()
    private val priority = ArrayDeque<ByteArray>()
    private var lastResponseAt = Long.MIN_VALUE / 2

    /**
     * @return true 表示立即轉給 hev；false 表示已排隊（之後由 [drain] 放行）。
     * 佇列已滿時直接放行，寧可讓 hev 自行處理也不無限排隊。
     */
    @Synchronized
    fun offer(packet: ByteArray, length: Int, now: Long): Boolean {
        if (queue.isEmpty() && priority.isEmpty() && canSend(now)) {
            track(packet.copyOf(length), now)
            return true
        }
        if (queue.size >= maxQueue && priority.isEmpty()) return true
        queue.addLast(packet.copyOf(length))
        return false
    }

    /** 排在所有應用查詢之前（映射重放用）。 */
    @Synchronized
    fun enqueuePriority(packets: List<ByteArray>) {
        priority.addAll(packets)
    }

    /** 收到一個映射 DNS 回覆（不在這裡放行，交給 [drain] 依間隔放行）。 */
    @Synchronized
    fun onResponse(packet: ByteArray, length: Int, now: Long) {
        val key = replyKey(packet, length)
        val index = inFlight.indexOfFirst { it.key == key }
        if (index >= 0) inFlight.removeAt(index) else if (inFlight.isNotEmpty() && key == NO_KEY) inFlight.removeAt(0)
        lastResponseAt = now
    }

    /** 回傳現在要送給 hev 的封包：逾時未回覆的重送，以及可以放行的排隊查詢。 */
    fun drain(now: Long): List<ByteArray> {
        var dropped: ArrayList<ByteArray>? = null
        val out = ArrayList<ByteArray>(2)
        synchronized(this) {
            val iterator = inFlight.iterator()
            while (iterator.hasNext()) {
                val flight = iterator.next()
                if (now - flight.sentAt < retryMs) continue
                if (flight.attempts >= maxAttempts) {
                    iterator.remove()
                    (dropped ?: ArrayList<ByteArray>().also { dropped = it }) += flight.packet
                } else {
                    flight.attempts++
                    flight.sentAt = now
                    out += flight.packet
                }
            }
            while ((priority.isNotEmpty() || queue.isNotEmpty()) && canSend(now)) {
                val packet = if (priority.isNotEmpty()) priority.removeFirst() else queue.removeFirst()
                track(packet, now)
                out += packet
            }
        }
        dropped?.forEach(onGiveUp)
        return out
    }

    @get:Synchronized
    val queued: Int get() = queue.size + priority.size

    /** 是否還有需要放行執行緒處理的工作（排隊中或在途等待重送）。 */
    @get:Synchronized
    val busy: Boolean get() = queue.isNotEmpty() || priority.isNotEmpty() || inFlight.isNotEmpty()

    private fun track(packet: ByteArray, now: Long) {
        inFlight += InFlight(queryKey(packet, packet.size), packet, now, 1)
    }

    private fun canSend(now: Long): Boolean = inFlight.size < limit && now - lastResponseAt >= minGapMs

    companion object {
        private const val NO_KEY = -1L

        /** 查詢的鍵：用戶端（來源）埠 + DNS 事務 ID（只處理 IPv4 UDP；映射 DNS 只有 IPv4）。 */
        internal fun queryKey(packet: ByteArray, length: Int): Long {
            val ihl = udpHeaderOffset(packet, length) ?: return NO_KEY
            return (u16(packet, ihl).toLong() shl 16) or u16(packet, ihl + 8).toLong()
        }

        /** 回覆的鍵：用戶端（目的）埠 + DNS 事務 ID。 */
        internal fun replyKey(packet: ByteArray, length: Int): Long {
            val ihl = udpHeaderOffset(packet, length) ?: return NO_KEY
            return (u16(packet, ihl + 2).toLong() shl 16) or u16(packet, ihl + 8).toLong()
        }

        private fun udpHeaderOffset(packet: ByteArray, length: Int): Int? {
            if (length < 1 || (packet[0].toInt() ushr 4) != 4) return null
            val ihl = (packet[0].toInt() and 0x0F) * 4
            return if (ihl >= 20 && ihl + 10 <= length) ihl else null
        }

        private fun u16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }
}
