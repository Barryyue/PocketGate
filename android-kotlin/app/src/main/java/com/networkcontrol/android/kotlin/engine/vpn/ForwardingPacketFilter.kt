package com.networkcontrol.android.kotlin.engine.vpn

import com.networkcontrol.android.kotlin.core.model.L4Protocol
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/** 轉發模式下一條新連線的去向。 */
enum class ForwardingDecision {
    /** 交給上游代理。 */
    Forward,

    /** 直接拒絕（RST / ICMP 不可達），只記錄。 */
    Block,

    /** 拒絕並建立詢問；使用者允許後，下一次連線會被轉發。 */
    BlockAndAsk,
}

class ForwardingCounters {
    val packets = AtomicLong()
    val forwarded = AtomicLong()
    val blocked = AtomicLong()
    val udpDropped = AtomicLong()
    val lookups = AtomicLong()
    val unknownOwner = AtomicLong()
    val dnsMapped = AtomicLong()
}

/**
 * 轉發模式的封包過濾層：位於 VPN 虛擬網卡與 hev-socks5-tunnel 之間。
 *
 * - 只在新連線（TCP SYN、UDP 新五元組）上查詢 UID 並判決，結果按五元組緩存；
 *   既有 TCP 連線的後續封包走快速路徑，不做完整解析。
 * - 發往 hev 映射 DNS 的查詢直接放行；hev 回覆的 DNS 封包會被監聽，
 *   以便把映射位址還原成域名，用於記錄與詢問。
 * - 拒絕時立即回送 RST / ICMP，讓應用快速失敗，而不是等待逾時。
 *
 * 執行緒：[onTunPacket] 只由虛擬網卡讀取執行緒呼叫，[onHevPacket] 只由 hev 側讀取執行緒呼叫；
 * 共享的域名表與緩存以鎖保護。
 */
class ForwardingPacketFilter(
    private val ownerOf: (L4Protocol, InetSocketAddress, InetSocketAddress) -> Int?,
    private val decide: (uid: Int?) -> ForwardingDecision,
    private val onBlocked: (PacketInfo, uid: Int?, ask: Boolean) -> Unit,
    private val blockUdp: () -> Boolean,
    private val dnsAddress: InetAddress = ForwardingDefaults.MapDnsAddress,
    private val dnsPort: Int = ForwardingDefaults.MAP_DNS_PORT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 8192,
) {
    val counters = ForwardingCounters()

    private val cacheLock = Any()
    private val decisions = HashMap<FlowKey, CachedDecision>()
    private val namesLock = Any()
    private val names = object : LinkedHashMap<InetAddress, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InetAddress, String>?): Boolean =
            size > maxEntries
    }

    /** 規則或路由計畫改變時清空判決緩存，讓下一條連線重新判決。 */
    fun invalidate() {
        synchronized(cacheLock) { decisions.clear() }
    }

    fun hostnameFor(address: InetAddress): String? = synchronized(namesLock) { names[address] }

    /**
     * 處理一個從虛擬網卡讀到的封包。
     * @return true 表示交給 hev 轉發；false 表示丟棄（必要時已經透過 [writer] 回送拒絕封包）。
     */
    fun onTunPacket(packet: ByteArray, length: Int, writer: PacketWriter): Boolean {
        counters.packets.incrementAndGet()
        if (isEstablishedTcp(packet, length)) {
            counters.forwarded.incrementAndGet()
            return true
        }
        val info = IpPacketParser.tryParse(packet, length) ?: run {
            // 無法解析（分片、未知協定）時交給 hev 自行處理，避免誤殺。
            counters.forwarded.incrementAndGet()
            return true
        }
        when (info.protocol) {
            L4Protocol.Tcp -> if (!info.isConnectionAttempt) {
                counters.forwarded.incrementAndGet()
                return true
            }
            L4Protocol.Udp -> {
                if (info.destination.port == dnsPort && info.destination.address == dnsAddress) {
                    counters.forwarded.incrementAndGet()
                    return true
                }
                if (blockUdp()) {
                    counters.udpDropped.incrementAndGet()
                    writer.write(IpPacketBuilder.icmpPortUnreachable(info.destination, info.source, packet.copyOfRange(0, length)))
                    return false
                }
            }
            else -> {
                counters.forwarded.incrementAndGet()
                return true
            }
        }

        val now = clock()
        val key = FlowKey(info.protocol, info.source, info.destination)
        val cached = synchronized(cacheLock) {
            decisions[key]?.takeIf { it.expiresAt > now }?.also {
                if (info.protocol == L4Protocol.Udp) it.expiresAt = now + UDP_TTL_MS
            }
        }
        val decision = cached?.decision ?: run {
            counters.lookups.incrementAndGet()
            val uid = ownerOf(info.protocol, info.source, info.destination)
            if (uid == null) counters.unknownOwner.incrementAndGet()
            val fresh = decide(uid)
            remember(key, fresh, now, info.protocol)
            if (fresh != ForwardingDecision.Forward) {
                onBlocked(info, uid, fresh == ForwardingDecision.BlockAndAsk)
            }
            fresh
        }

        if (decision == ForwardingDecision.Forward) {
            counters.forwarded.incrementAndGet()
            return true
        }
        counters.blocked.incrementAndGet()
        reject(packet, length, info, writer)
        return false
    }

    /** 處理一個 hev 寫回虛擬網卡的封包：只監聽映射 DNS 的回覆。 */
    fun onHevPacket(packet: ByteArray, length: Int) {
        if (!isUdpFromPort(packet, length, dnsPort)) return
        val info = IpPacketParser.tryParse(packet, length) ?: return
        if (info.protocol != L4Protocol.Udp || info.source.address != dnsAddress) return
        val end = (info.payloadOffset + info.payloadLength).coerceAtMost(length)
        val answers = DnsAnswers.parseA(packet, info.payloadOffset, end)
        if (answers.isEmpty()) return
        synchronized(namesLock) {
            answers.forEach { (name, address) -> names[address] = name }
        }
        counters.dnsMapped.addAndGet(answers.size.toLong())
    }

    private fun remember(key: FlowKey, decision: ForwardingDecision, now: Long, protocol: L4Protocol) {
        val ttl = if (protocol == L4Protocol.Udp) UDP_TTL_MS else TCP_TTL_MS
        synchronized(cacheLock) {
            if (decisions.size >= maxEntries) {
                decisions.entries.removeAll { it.value.expiresAt <= now }
                if (decisions.size >= maxEntries) decisions.clear()
            }
            decisions[key] = CachedDecision(decision, now + ttl)
        }
    }

    private fun reject(packet: ByteArray, length: Int, info: PacketInfo, writer: PacketWriter) {
        when (info.protocol) {
            L4Protocol.Tcp -> writer.write(
                IpPacketBuilder.tcpReset(info.destination, info.source, info.tcpSequence + 1),
            )
            L4Protocol.Udp -> writer.write(
                IpPacketBuilder.icmpPortUnreachable(info.destination, info.source, packet.copyOfRange(0, length)),
            )
            else -> Unit
        }
    }

    private data class FlowKey(
        val protocol: L4Protocol,
        val source: InetSocketAddress,
        val destination: InetSocketAddress,
    )

    private class CachedDecision(val decision: ForwardingDecision, @Volatile var expiresAt: Long)

    companion object {
        /** SYN 重傳通常在數秒內，緩存稍長即可。 */
        const val TCP_TTL_MS = 10_000L

        /** UDP 沒有握手，每個封包都會刷新期限。 */
        const val UDP_TTL_MS = 60_000L

        private const val PROTOCOL_TCP = 6
        private const val PROTOCOL_UDP = 17

        /**
         * 快速判斷「不是新連線的 TCP 封包」：IPv4 / 無擴展頭的 IPv6、TCP、沒有單獨的 SYN。
         * 不解析位址，避免為既有連線的每個封包分配物件。
         */
        internal fun isEstablishedTcp(packet: ByteArray, length: Int): Boolean {
            if (length < 1) return false
            val offset = when ((packet[0].toInt() ushr 4) and 0x0F) {
                4 -> {
                    if (length < 20) return false
                    if ((packet[9].toInt() and 0xFF) != PROTOCOL_TCP) return false
                    if ((readU16(packet, 6) and 0x3FFF) != 0) return false
                    (packet[0].toInt() and 0x0F) * 4
                }
                6 -> {
                    if (length < 40) return false
                    if ((packet[6].toInt() and 0xFF) != PROTOCOL_TCP) return false
                    40
                }
                else -> return false
            }
            if (offset < 20 || offset + 14 > length) return false
            val flags = packet[offset + 13].toInt() and 0x1F
            return (flags and (TcpFlags.SYN or TcpFlags.ACK)) != TcpFlags.SYN
        }

        internal fun isUdpFromPort(packet: ByteArray, length: Int, port: Int): Boolean {
            if (length < 1) return false
            val offset = when ((packet[0].toInt() ushr 4) and 0x0F) {
                4 -> {
                    if (length < 28 || (packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return false
                    (packet[0].toInt() and 0x0F) * 4
                }
                6 -> {
                    if (length < 48 || (packet[6].toInt() and 0xFF) != PROTOCOL_UDP) return false
                    40
                }
                else -> return false
            }
            if (offset + 8 > length) return false
            return readU16(packet, offset) == port
        }

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }
}

/** 解析 DNS 回覆中的 A 記錄（支援名稱壓縮）。只用於監聽 hev 映射 DNS 的回覆。 */
object DnsAnswers {
    private const val HEADER_LENGTH = 12
    private const val TYPE_A = 1
    private const val CLASS_IN = 1
    private const val MAX_JUMPS = 16

    fun parseA(message: ByteArray, start: Int, end: Int): List<Pair<String, InetAddress>> {
        if (end - start < HEADER_LENGTH || end > message.size) return emptyList()
        val flags = u16(message, start + 2)
        if ((flags and 0x8000) == 0) return emptyList()
        if ((flags and 0x000F) != 0) return emptyList()
        val questions = u16(message, start + 4)
        val answers = u16(message, start + 6)
        if (answers == 0) return emptyList()

        var offset = start + HEADER_LENGTH
        repeat(questions) {
            offset = skipName(message, start, offset, end) ?: return emptyList()
            offset += 4
            if (offset > end) return emptyList()
        }
        val result = ArrayList<Pair<String, InetAddress>>(answers)
        repeat(answers) {
            val name = readName(message, start, offset, end) ?: return result
            offset = skipName(message, start, offset, end) ?: return result
            if (offset + 10 > end) return result
            val type = u16(message, offset)
            val klass = u16(message, offset + 2)
            val rdLength = u16(message, offset + 8)
            offset += 10
            if (offset + rdLength > end) return result
            if (type == TYPE_A && klass == CLASS_IN && rdLength == 4 && name.isNotEmpty()) {
                val address = Inet4Address.getByAddress(message.copyOfRange(offset, offset + 4))
                result += name to address
            }
            offset += rdLength
        }
        return result
    }

    private fun skipName(message: ByteArray, base: Int, from: Int, end: Int): Int? {
        var offset = from
        while (true) {
            if (offset >= end) return null
            val length = message[offset].toInt() and 0xFF
            when {
                length == 0 -> return offset + 1
                (length and 0xC0) == 0xC0 -> return if (offset + 2 <= end) offset + 2 else null
                length > 63 -> return null
                else -> offset += 1 + length
            }
        }
    }

    private fun readName(message: ByteArray, base: Int, from: Int, end: Int): String? {
        val builder = StringBuilder()
        var offset = from
        var jumps = 0
        while (true) {
            if (offset >= end) return null
            val length = message[offset].toInt() and 0xFF
            when {
                length == 0 -> return builder.toString()
                (length and 0xC0) == 0xC0 -> {
                    if (offset + 2 > end || ++jumps > MAX_JUMPS) return null
                    val pointer = ((length and 0x3F) shl 8) or (message[offset + 1].toInt() and 0xFF)
                    offset = base + pointer
                }
                length > 63 -> return null
                else -> {
                    if (offset + 1 + length > end) return null
                    if (builder.isNotEmpty()) builder.append('.')
                    for (i in 1..length) {
                        val b = message[offset + i].toInt() and 0xFF
                        builder.append(if (b in 'A'.code..'Z'.code) (b + 32).toChar() else b.toChar())
                    }
                    if (builder.length > 253) return null
                    offset += 1 + length
                }
            }
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

/**
 * 轉發模式的判決：沿用黑洞模式的路由計畫（規則、模式、專注、鎖屏、可信應用），
 * 差別只在「放行」改為交給上游代理，「阻止 / 詢問」改由過濾層逐連線丟棄。
 */
object ForwardingPolicy {
    fun decide(plan: com.networkcontrol.android.kotlin.core.model.VpnRoutingPlan, uid: Int?): ForwardingDecision {
        val mode = plan.mode
        // 查不到擁有者（getConnectionOwnerUid 失敗）時無法詢問：除「全部阻止」外一律放行，並計數。
        if (uid == null) {
            return if (mode == com.networkcontrol.android.kotlin.core.model.FilterMode.BlockAll) {
                ForwardingDecision.Block
            } else {
                ForwardingDecision.Forward
            }
        }
        // 系統核心 UID 無法按應用排除，與黑洞模式一致地放行。
        if (VpnRoutingPlanner.isSystemUid(uid)) return ForwardingDecision.Forward
        val routing = plan.find(uid) ?: return when (mode) {
            com.networkcontrol.android.kotlin.core.model.FilterMode.Disabled,
            com.networkcontrol.android.kotlin.core.model.FilterMode.Blacklist,
            -> ForwardingDecision.Forward
            com.networkcontrol.android.kotlin.core.model.FilterMode.Ask -> ForwardingDecision.BlockAndAsk
            else -> ForwardingDecision.Block
        }
        return when (routing.decision) {
            com.networkcontrol.android.kotlin.core.model.AppRoutingDecision.Bypass -> ForwardingDecision.Forward
            com.networkcontrol.android.kotlin.core.model.AppRoutingDecision.Block -> ForwardingDecision.Block
            com.networkcontrol.android.kotlin.core.model.AppRoutingDecision.BlockAndAsk -> ForwardingDecision.BlockAndAsk
        }
    }
}
