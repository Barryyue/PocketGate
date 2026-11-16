package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.engine.dns.DnsMessage
import com.networkcontrol.android.kotlin.engine.dns.DohResolver
import com.networkcontrol.android.kotlin.engine.dns.EncryptedDnsTargets
import com.networkcontrol.android.kotlin.engine.vpn.DnsRedirect
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingDecision
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingDefaults
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingPacketFilter
import com.networkcontrol.android.kotlin.engine.vpn.IpPacketBuilder
import com.networkcontrol.android.kotlin.engine.vpn.IpPacketParser
import com.networkcontrol.android.kotlin.engine.vpn.PacketWriter
import com.networkcontrol.android.kotlin.engine.vpn.TcpFlags
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class DnsProtectionTest {
    // ---- DNS 報文 ----

    /** 以查詢報文為基礎組出回覆：答案用名稱壓縮指標指回問題。 */
    private fun reply(query: ByteArray, rcode: Int, vararg answers: Pair<Int, ByteArray>, ttl: Int = 300): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(query, 0, 2)
        out.write(0x81); out.write(0x80 or rcode)
        out.write(0); out.write(1)
        out.write(0); out.write(answers.size)
        repeat(4) { out.write(0) }
        out.write(query, 12, query.size - 12)
        answers.forEach { (type, data) ->
            out.write(0xC0); out.write(12)
            out.write(type ushr 8); out.write(type and 0xFF)
            out.write(0); out.write(1)
            out.write(ttl ushr 24); out.write(ttl ushr 16 and 0xFF); out.write(ttl ushr 8 and 0xFF); out.write(ttl and 0xFF)
            out.write(0); out.write(data.size)
            out.write(data)
        }
        return out.toByteArray()
    }

    @Test
    fun buildsQueryAndParsesCompressedAnswers() {
        val query = DnsMessage.buildQuery("Example.org.", DnsMessage.TYPE_A)
        assertEquals(0, query[0].toInt())
        assertEquals(1, query[5].toInt())
        val response = reply(query, 0, DnsMessage.TYPE_A to byteArrayOf(93, -72, -41, 14), DnsMessage.TYPE_AAAA to ByteArray(16))
        val records = DnsMessage.parseAnswers(response, DnsMessage.TYPE_A)
        assertEquals(1, records.size)
        assertEquals("93.184.215.14", records[0].address.hostAddress)
        assertEquals(300L, records[0].ttlSeconds)
        assertEquals(1, DnsMessage.parseAnswers(response, DnsMessage.TYPE_AAAA).size)
    }

    // ---- DoH ----

    private lateinit var http: MiniHttpServer
    private val dohHits = AtomicInteger()
    private var dohDown = false

    @Before
    fun startDoh() {
        http = MiniHttpServer { path, _ ->
            dohHits.incrementAndGet()
            if (dohDown || path != "/dns-query") return@MiniHttpServer MiniHttpServer.Response(503)
            MiniHttpServer.Response(200, mapOf("Content-Type" to "application/dns-message"), ByteArray(0))
        }
    }

    @After
    fun stopDoh() {
        http.close()
    }

    /** MiniHttpServer 不讀請求本文；這裡改用可以讀本文的處理器。 */
    private fun resolverWith(
        answer: (ByteArray) -> ByteArray?,
        fallback: ((String) -> Array<InetAddress>)? = null,
        clock: () -> Long = System::currentTimeMillis,
    ): DohResolver = DohResolver(
        url = { "http://127.0.0.1:${http.port}/dns-query" },
        open = { url -> FakeDohConnection(url, answer).also { dohHits.incrementAndGet() } },
        fallback = { fallback },
        clock = clock,
    )

    @Test
    fun dohResolvesCachesAndHandlesNxdomain() {
        val resolver = resolverWith({ query ->
            val name = String(query, 13, query[12].toInt())
            if (name == "missing") reply(query, 3) else reply(query, 0, DnsMessage.TYPE_A to byteArrayOf(10, 0, 0, 7))
        })
        val before = dohHits.get()
        assertEquals("10.0.0.7", resolver.resolve("example.org")[0].hostAddress)
        assertEquals("10.0.0.7", resolver.resolve("EXAMPLE.org")[0].hostAddress)
        assertEquals(1, dohHits.get() - before) // 第二次走快取
        assertThrowsUnknownHost { resolver.resolve("missing.test") }
    }

    @Test
    fun dohFallsBackToAaaaWhenNoIpv4() {
        val resolver = resolverWith({ query ->
            val type = query[query.size - 3].toInt()
            if (type == DnsMessage.TYPE_A) reply(query, 0) else reply(query, 0, DnsMessage.TYPE_AAAA to ByteArray(16).also { it[15] = 1 })
        })
        assertEquals("0:0:0:0:0:0:0:1", resolver.resolve("v6only.test")[0].hostAddress)
    }

    @Test
    fun circuitBreakerSwitchesToFallbackAndNoFallbackFails() {
        var now = 1_000L
        val resolver = resolverWith({ null }, fallback = { arrayOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))) }, clock = { now })
        repeat(3) { assertEquals("1.2.3.4", resolver.resolve("a$it.test")[0].hostAddress) }
        val before = dohHits.get()
        resolver.resolve("b.test")
        assertEquals(before, dohHits.get()) // 斷路器打開：不再嘗試 DoH
        now += 200_000
        resolver.resolve("c.test")
        assertTrue(dohHits.get() > before)

        val strict = resolverWith({ null })
        try {
            strict.resolve("x.test")
            throw AssertionError("expected failure")
        } catch (_: IOException) {
        }
    }

    private fun assertThrowsUnknownHost(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected UnknownHostException")
        } catch (_: UnknownHostException) {
        }
    }

    private class FakeDohConnection(url: java.net.URL, private val answer: (ByteArray) -> ByteArray?) : HttpURLConnection(url) {
        private val body = java.io.ByteArrayOutputStream()
        private var response: ByteArray? = null
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream(): java.io.OutputStream = body
        override fun getResponseCode(): Int {
            if (response == null) response = answer(body.toByteArray())
            return if (response == null) 503 else 200
        }
        override fun getInputStream(): java.io.InputStream = (response ?: throw IOException("no response")).inputStream()
    }

    // ---- DNS 改寫（NAT） ----

    private val client = InetSocketAddress("10.111.222.1", 53000)
    private val google = InetSocketAddress("8.8.8.8", 53)
    private val mapDns = InetSocketAddress(ForwardingDefaults.MapDnsAddress, 53)

    private fun checksumOk(packet: ByteArray): Boolean {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (DnsRedirect.checksum(packet, 0, ihl, 0) != 0) return false
        val udpLength = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        var pseudo = 0L
        for (i in 12 until 20 step 2) pseudo += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        pseudo += 17 + udpLength
        return DnsRedirect.checksum(packet, ihl, udpLength, pseudo) == 0
    }

    @Test
    fun redirectRewritesQueryAndRestoresReply() {
        val nat = DnsRedirect()
        val query = IpPacketBuilder.udp4(client, google, DnsMessage.buildQuery("example.org", 1))
        assertTrue(nat.redirectQuery(query, query.size))
        val rewritten = IpPacketParser.tryParse(query, query.size)!!
        assertEquals(ForwardingDefaults.MapDnsAddress, rewritten.destination.address)
        assertEquals(53000, rewritten.source.port)
        assertTrue(checksumOk(query))

        val answer = IpPacketBuilder.udp4(mapDns, client, ByteArray(20))
        assertTrue(nat.restoreReply(answer, answer.size))
        val restored = IpPacketParser.tryParse(answer, answer.size)!!
        assertEquals(google.address, restored.source.address)
        assertTrue(checksumOk(answer))

        // 沒有對應查詢的回覆保持不變
        val other = IpPacketBuilder.udp4(mapDns, InetSocketAddress("10.111.222.1", 1), ByteArray(4))
        val copy = other.copyOf()
        assertFalse(nat.restoreReply(other, other.size))
        assertArrayEquals(copy, other)
    }

    private class Writer : PacketWriter {
        val packets = mutableListOf<ByteArray>()
        override fun write(packet: ByteArray) {
            packets += packet
        }
    }

    @Test
    fun filterRedirectsAppDnsAndBlocksTcpDnsWhenProtected() {
        var protect = true
        val filter = ForwardingPacketFilter(
            ownerOf = { _, _, _ -> 10100 },
            decide = { _, _, _ -> ForwardingDecision.Forward },
            onBlocked = { _, _, _ -> },
            blockUdp = { false },
            redirectDns = { protect },
        )
        val writer = Writer()
        val query = IpPacketBuilder.udp4(client, google, DnsMessage.buildQuery("example.org", 1))
        assertTrue(filter.onTunPacket(query, query.size, writer))
        assertEquals(ForwardingDefaults.MapDnsAddress, IpPacketParser.tryParse(query, query.size)!!.destination.address)
        assertEquals(1L, filter.counters.dnsRedirected.get())

        val reply = IpPacketBuilder.udp4(mapDns, client, ByteArray(12))
        filter.onHevPacket(reply, reply.size)
        assertEquals(google.address, IpPacketParser.tryParse(reply, reply.size)!!.source.address)

        val syn = ByteArray(40).also { p ->
            p[0] = 0x45; p[3] = 40; p[8] = 64; p[9] = 6
            client.address.address.copyInto(p, 12); google.address.address.copyInto(p, 16)
            p[20] = (53001 ushr 8).toByte(); p[21] = 53001.toByte(); p[23] = 53
            p[32] = 0x50; p[33] = TcpFlags.SYN.toByte()
        }
        assertFalse(filter.onTunPacket(syn, syn.size, writer))
        assertNotNull(writer.packets.lastOrNull())

        protect = false
        filter.invalidate()
        val plain = IpPacketBuilder.udp4(InetSocketAddress("10.111.222.1", 53002), google, ByteArray(20))
        assertTrue(filter.onTunPacket(plain, plain.size, writer))
        assertEquals(google.address, IpPacketParser.tryParse(plain, plain.size)!!.destination.address)
    }

    @Test
    fun encryptedDnsTargetsAreRecognised() {
        val ip = { s: String -> InetAddress.getByName(s) }
        assertTrue(EncryptedDnsTargets.matches(853, ip("203.0.113.5"), null))
        assertTrue(EncryptedDnsTargets.matches(443, ip("100.64.0.9"), "dns.google"))
        assertTrue(EncryptedDnsTargets.matches(443, ip("100.64.0.9"), "abc123.dns.nextdns.io"))
        assertTrue(EncryptedDnsTargets.matches(443, ip("1.1.1.1"), null))
        assertTrue(EncryptedDnsTargets.matches(443, ip("2606:4700:4700::1111"), null))
        assertFalse(EncryptedDnsTargets.matches(443, ip("100.64.0.9"), "www.google.com"))
        assertFalse(EncryptedDnsTargets.matches(80, ip("1.1.1.1"), null))
    }
}
