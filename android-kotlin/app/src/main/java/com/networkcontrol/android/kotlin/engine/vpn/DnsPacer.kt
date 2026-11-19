package com.networkcontrol.android.kotlin.engine.vpn

/**
 * 映射 DNS 查詢的放行與重送。
 *
 * 曾經觀察到「hev 突發時丟 DNS 查詢」，根因其實是轉送用的 socket 對不保留封包邊界（見 [ForwardingRelay]），已經修正。
 * 這裡保留兩件事：
 * 1. hev 重啟後的映射重放（[FakeIpMappings]）經 [enqueuePriority] 排在所有應用查詢之前，hev 依原順序分配位址；
 * 2. 在途查詢 [retryMs] 內沒有回覆就由本應用重送，最多 [maxAttempts] 次，比 Android 解析器 5 秒後才重送快得多。
 * 回覆以「用戶端埠 + DNS 事務 ID」對應到在途查詢；同時在途最多 [limit] 個，避免異常時無限堆積。
 *
 * hev 重啟後的映射重放（見 [FakeIpMappings]）經 [enqueuePriority] 排在所有應用查詢之前；
 * 重放期間應用的查詢一律排隊，確保 hev 依原順序分配位址。
 *
 * 執行緒：[offer] 在虛擬網卡讀取執行緒、[onResponse] 在 hev 側執行緒、[drain] 在放行執行緒呼叫，以物件鎖同步。
 */
class DnsPacer(
    private val limit: Int = 64,
    private val retryMs: Long = 100,
    private val maxAttempts: Int = 3,
    /** 收到回覆後到放行下一個查詢之間的最短間隔。 */
    private val minGapMs: Long = 0,
    private val maxQueue: Int = 512,
    /** 查詢多次重送仍無回覆而放棄時呼叫（在鎖外呼叫）。 */
    private val onGiveUp: (ByteArray) -> Unit = {},
) {
    private class InFlight(val key: Long, val packet: ByteArray, var sentAt: Long, var attempts: Int)

    private val lock = Object()
    private val inFlight = ArrayList<InFlight>(limit)
    private val queue = ArrayDeque<ByteArray>()
    private val priority = ArrayDeque<ByteArray>()
    private var lastResponseAt = Long.MIN_VALUE / 2

    /**
     * @return true 表示立即轉給 hev；false 表示已排隊（之後由 [drain] 放行）。
     * 佇列已滿時直接放行，寧可讓 hev 自行處理也不無限排隊。
     */
    fun offer(packet: ByteArray, length: Int, now: Long): Boolean = synchronized(lock) {
        if (queue.isEmpty() && priority.isEmpty() && canSend(now)) {
            track(packet.copyOf(length), now)
            lock.notifyAll()
            return@synchronized true
        }
        if (queue.size >= maxQueue && priority.isEmpty()) return@synchronized true
        queue.addLast(packet.copyOf(length))
        lock.notifyAll()
        false
    }

    /** 排在所有應用查詢之前（映射重放用）。 */
    fun enqueuePriority(packets: List<ByteArray>) = synchronized(lock) {
        priority.addAll(packets)
        if (packets.isNotEmpty()) lock.notifyAll()
    }

    /** 收到一個映射 DNS 回覆（不在這裡放行，交給 [drain] 依間隔放行）。 */
    fun onResponse(packet: ByteArray, length: Int, now: Long) = synchronized(lock) {
        val key = replyKey(packet, length)
        val index = inFlight.indexOfFirst { it.key == key }
        if (index >= 0) inFlight.removeAt(index) else if (inFlight.isNotEmpty() && key == NO_KEY) inFlight.removeAt(0)
        lastResponseAt = now
        if (queue.isNotEmpty() || priority.isNotEmpty()) lock.notifyAll()
    }

    /** 新隧道啟動前清空上一輪遺留的在途與排隊查詢。 */
    fun clear() = synchronized(lock) {
        inFlight.clear()
        queue.clear()
        priority.clear()
        lastResponseAt = Long.MIN_VALUE / 2
        lock.notifyAll()
    }

    /** 喚醒休眠中的放行執行緒（關閉轉送層時呼叫）。 */
    fun wake() = synchronized(lock) {
        lock.notifyAll()
    }

    /**
     * 事件驅動等待：空閒時無限期休眠（零 CPU 喚醒）；有排隊或逾時重送時只睡到下一個到期時間點。
     */
    fun awaitWork(now: () -> Long, isStopped: () -> Boolean) = synchronized(lock) {
        if (isStopped()) return
        val current = now()
        val hasQueued = priority.isNotEmpty() || queue.isNotEmpty()
        if (hasQueued && canSend(current)) return
        val waitMs = when {
            hasQueued && inFlight.size < limit -> (lastResponseAt + minGapMs - current).coerceAtLeast(1L)
            inFlight.isNotEmpty() -> {
                var earliest = Long.MAX_VALUE
                for (i in 0 until inFlight.size) {
                    val due = inFlight[i].sentAt + retryMs
                    if (due < earliest) earliest = due
                }
                (earliest - current).coerceAtLeast(1L)
            }
            else -> 0L // 0 表示完全空閒，無限期等待直到 offer / enqueuePriority / wake 喚醒
        }
        try {
            if (waitMs == 0L) lock.wait() else lock.wait(waitMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 回傳現在要送給 hev 的封包：逾時未回覆的重送，以及可以放行的排隊查詢。 */
    fun drain(now: Long): List<ByteArray> {
        var dropped: ArrayList<ByteArray>? = null
        val out = ArrayList<ByteArray>(2)
        synchronized(lock) {
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

    val queued: Int get() = synchronized(lock) { queue.size + priority.size }

    /** 是否還有需要放行執行緒處理的工作（排隊中或在途等待重送）。 */
    val busy: Boolean get() = synchronized(lock) { queue.isNotEmpty() || priority.isNotEmpty() || inFlight.isNotEmpty() }

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
