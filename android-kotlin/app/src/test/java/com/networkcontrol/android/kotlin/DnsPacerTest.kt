package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.engine.vpn.DnsPacer
import com.networkcontrol.android.kotlin.engine.vpn.IpPacketBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class DnsPacerTest {
    private val mapDns = InetSocketAddress("10.111.222.3", 53)

    private fun query(port: Int, id: Int): ByteArray =
        IpPacketBuilder.udp4(InetSocketAddress("10.111.222.1", port), mapDns, byteArrayOf((id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1))

    private fun reply(port: Int, id: Int): ByteArray =
        IpPacketBuilder.udp4(mapDns, InetSocketAddress("10.111.222.1", port), byteArrayOf((id ushr 8).toByte(), id.toByte(), 0x81.toByte(), 0x80.toByte()))

    private fun DnsPacer.offer(packet: ByteArray, now: Long) = offer(packet, packet.size, now)

    @Test
    fun onlyOneQueryInFlightAndOrderIsKept() {
        val pacer = DnsPacer(minGapMs = 2)
        val sent = (0 until 5).map { pacer.offer(query(1000 + it, it), now = 0) }
        assertEquals(listOf(true, false, false, false, false), sent)
        assertEquals(4, pacer.queued)
        assertTrue(pacer.drain(now = 10).isEmpty())
        pacer.onResponse(reply(1000, 0).let { it }, reply(1000, 0).size, now = 11)
        assertTrue(pacer.drain(now = 12).isEmpty()) // 間隔未到
        val next = pacer.drain(now = 13)
        assertEquals(1, next.size)
        assertEquals(DnsPacer.queryKey(query(1001, 1), query(1001, 1).size), DnsPacer.queryKey(next[0], next[0].size))
    }

    @Test
    fun unansweredQueryIsResentThenGivenUp() {
        val pacer = DnsPacer(retryMs = 25, maxAttempts = 3)
        val q = query(2000, 7)
        assertTrue(pacer.offer(q, now = 0))
        assertTrue(pacer.drain(now = 10).isEmpty())
        assertEquals(1, pacer.drain(now = 25).size) // 第 2 次
        assertEquals(1, pacer.drain(now = 50).size) // 第 3 次
        assertTrue(pacer.drain(now = 75).isEmpty()) // 放棄
        assertFalse(pacer.busy)
    }

    @Test
    fun replyMatchesTheRightQuery() {
        val pacer = DnsPacer(limit = 2, minGapMs = 0)
        pacer.offer(query(3000, 1), now = 0)
        pacer.offer(query(3001, 2), now = 0)
        val second = reply(3001, 2)
        pacer.onResponse(second, second.size, now = 5)
        // 只剩 3000/1 在途：逾時後只重送它
        val resent = pacer.drain(now = 30)
        assertEquals(1, resent.size)
        assertEquals(DnsPacer.queryKey(query(3000, 1), query(3000, 1).size), DnsPacer.queryKey(resent[0], resent[0].size))
    }

    @Test
    fun queryRightAfterResponseIsDelayed() {
        val pacer = DnsPacer(minGapMs = 2)
        pacer.offer(query(4000, 1), now = 0)
        val r = reply(4000, 1)
        pacer.onResponse(r, r.size, now = 3)
        assertFalse(pacer.offer(query(4001, 2), now = 3))
        assertEquals(1, pacer.drain(now = 5).size)
    }

    @Test
    fun fullQueueFallsBackToImmediate() {
        val pacer = DnsPacer(maxQueue = 2)
        pacer.offer(query(5000, 0), now = 0)
        assertFalse(pacer.offer(query(5001, 1), now = 0))
        assertFalse(pacer.offer(query(5002, 2), now = 0))
        assertTrue(pacer.offer(query(5003, 3), now = 0))
    }
}
