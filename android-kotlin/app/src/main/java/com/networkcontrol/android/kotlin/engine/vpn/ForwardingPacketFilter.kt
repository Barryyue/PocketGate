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

    /** 目標域名命中攔截列表：拒絕並記錄，不詢問。 */
    Blocklisted,
}

class ForwardingCounters {
    val packets = AtomicLong()
    val forwarded = AtomicLong()
    val blocked = AtomicLong()
    val udpDropped = AtomicLong()
    val lookups = AtomicLong()
    val unknownOwner = AtomicLong()
    val dnsMapped = AtomicLong()
    val udpFastPath = AtomicLong()
    val dnsRedirected = AtomicLong()
    val dnsBypassBlocked = AtomicLong()
    val connectionsCut = AtomicLong()
    val dnsQueued = AtomicLong()

    /** 重放中、暫時丟棄的新連線（發往尚未恢復的假位址）。 */
    val fakeIpHeld = AtomicLong()

    /** 發往 hev 不認識的假位址而被拒絕的新連線。 */
    val fakeIpRejected = AtomicLong()

    /** hev 啟動時重放的映射數。 */
    val mappingsReplayed = AtomicLong()

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
    private val decide: (uid: Int?, info: PacketInfo, hostname: String?) -> ForwardingDecision,
    private val onBlocked: (PacketInfo, uid: Int?, ask: Boolean) -> Unit,
    private val blockUdp: () -> Boolean,
    /** 把應用直接發往其他 DNS 伺服器的查詢改寫到映射 DNS（防止繞過本機 DNS）。 */
    private val redirectDns: () -> Boolean = { false },
    /** 命中攔截列表的新連線；預設與一般拒絕相同。 */
    private val onBlocklisted: (PacketInfo, uid: Int?) -> Unit = { info, uid -> onBlocked(info, uid, false) },
    /** 新連線被判定轉發時呼叫（只在新連線上，不是每個封包），用於記錄已允許的連線。 */
    private val onForwarded: (PacketInfo, uid: Int?) -> Unit = { _, _ -> },
    private val dnsAddress: InetAddress = ForwardingDefaults.MapDnsAddress,
    private val dnsPort: Int = ForwardingDefaults.MAP_DNS_PORT,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxEntries: Int = 8192,
) {
    val counters = ForwardingCounters()
    private val dnsRedirect = DnsRedirect(dnsAddress.address)

    /**
     * 映射 DNS 查詢的排隊與重送（hev 重啟後的映射重放必須排在應用查詢之前），見 [DnsPacer]。
     */
    private val fakeIps = FakeIpMappings()


    private val dnsPacer = DnsPacer(
        onGiveUp = { packet ->
            if ((DnsPacer.queryKey(packet, packet.size) ushr 16) == FakeIpMappings.REPLAY_PORT.toLong()) {
                com.networkcontrol.android.kotlin.platform.AppLog.w("forwarding", "mapping replay gave up; remaining addresses dropped")
                fakeIps.abort()
            }
        },
    )

    /**
     * hev 剛啟動（映射表是空的）：按原位址順序重新查詢已知網域，讓應用快取中的假位址繼續有效。
     * 由轉送層在開始讀取虛擬網卡之前呼叫，重放的查詢排在所有應用查詢之前。
     */
    fun beginMappingReplay() {
        val plan = fakeIps.beginSession()
        if (plan.isEmpty()) return
        val source = InetSocketAddress(FakeDnsPool.Ipv4Address, FakeIpMappings.REPLAY_PORT)
        val target = InetSocketAddress(dnsAddress, dnsPort)
        val packets = plan.mapIndexedNotNull { index, name ->
            FakeIpMappings.buildQuery(name, (index + 1) and 0xFFFF)?.let { IpPacketBuilder.udp4(source, target, it) }
        }
        if (packets.size != plan.size) {
            // 有網域無法組成查詢：位址順序無法保證，放棄重放
            fakeIps.abort()
            return
        }
        dnsPacer.enqueuePriority(packets)
        counters.mappingsReplayed.addAndGet(packets.size.toLong())
        com.networkcontrol.android.kotlin.platform.AppLog.i("forwarding", "replaying ${packets.size} mapped names")
    }

    /** 由轉送層的放行執行緒呼叫：放行排隊中的查詢、重送逾時未回覆的查詢；回傳是否仍有待處理的查詢。 */
    fun drainDns(): Boolean {
        val writer = toHev ?: return false
        if (!dnsPacer.busy) return false
        dnsPacer.drain(clock()).forEach(writer::write)
        return dnsPacer.busy
    }

    /** 事件驅動等待下一次 DNS 放行或重送時機；空閒時完全休眠。 */
    fun awaitDnsWork(isStopped: () -> Boolean) {
        dnsPacer.awaitWork(clock, isStopped)
    }

    /** 喚醒休眠中的 DNS 放行執行緒（關閉轉送層時呼叫）。 */
    fun wakeDnsPump() {
        dnsPacer.wake()
    }

    /** 把查詢交給映射 DNS：可以立即放行就回傳 true，否則已排隊（之後由 [drainDns] 放行）。 */
    private fun forwardDnsQuery(packet: ByteArray, length: Int, now: Long): Boolean {
        val writer = toHev ?: return true
        dnsPacer.drain(now).forEach(writer::write)
        val immediate = dnsPacer.offer(packet, length, now)
        if (!immediate) counters.dnsQueued.incrementAndGet()
        return immediate
    }

    private val cacheLock = Any()
    private val decisions = HashMap<FlowKey, CachedDecision>()
    private val namesLock = Any()
    private val names = object : LinkedHashMap<InetAddress, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InetAddress, String>?): Boolean =
            size > maxEntries
    }

    /**
     * UDP 快速路徑：以固定 8 槽位扁平陣列記住最近已放行的 UDP 流（QUIC、影音多路並發不抖動），
     * 後續封包不必完整解析、配置物件或取鎖。
     * 只由虛擬網卡讀取執行緒存取；[invalidate] / [reset] 透過 [epoch] 讓它失效。
     */
    private val udpKeys = ByteArray(UDP_FAST_SLOTS * UDP_KEY_MAX)
    private val udpKeyLengths = IntArray(UDP_FAST_SLOTS)
    private val udpEpochs = IntArray(UDP_FAST_SLOTS) { -1 }
    private val udpUntil = LongArray(UDP_FAST_SLOTS)
    private var udpNextSlot = 0

    @Volatile
    private var epoch = 0

    /**
     * 已轉發的 TCP 連線（判決時的輸入），用於規則改變後重新判決並切斷已不允許的連線。
     * 只在新連線上寫入；連線結束後殘留的項目無害（切斷一條已經不存在的連線不會有任何效果）。
     */
    private class ActiveFlow(val uid: Int?, val info: PacketInfo, val hostname: String?, val createdAt: Long)

    private val activeTcp = object : LinkedHashMap<FlowKey, ActiveFlow>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FlowKey, ActiveFlow>?) = size > maxEntries
    }

    /** 待切斷的連線；只有非空時，既有連線的快速路徑才需要解析封包。 */
    private val doomed = HashSet<FlowKey>()

    @Volatile
    private var killPending = false

    /** 寫往 hev 側的出口（由轉送層設定）；切斷連線時用來通知 lwIP 關閉對上游的連線。 */
    @Volatile
    var toHev: PacketWriter? = null

    /**
     * 規則或路由計畫改變時呼叫：清空判決緩存，並重新判決已轉發的 TCP 連線；
     * 已不再允許的連線在下一個封包經過時被切斷（雙向送出精確序號的 RST）。
     */
    fun invalidate() {
        val snapshot: List<Pair<FlowKey, ActiveFlow>>
        synchronized(cacheLock) {
            decisions.clear()
            epoch++
            snapshot = activeTcp.entries.map { it.key to it.value }
        }
        if (snapshot.isEmpty()) return
        val now = clock()
        val cut = snapshot.filter { (_, flow) ->
            now - flow.createdAt < ACTIVE_TTL_MS &&
                runCatching { decide(flow.uid, flow.info, flow.hostname) }.getOrDefault(ForwardingDecision.Forward) != ForwardingDecision.Forward
        }.map { it.first }
        synchronized(cacheLock) {
            activeTcp.entries.removeAll { now - it.value.createdAt >= ACTIVE_TTL_MS }
            cut.forEach { key ->
                activeTcp.remove(key)
                doomed += key
            }
            killPending = doomed.isNotEmpty()
        }
    }

    /** 規則或使用者主動查詢時回傳的活躍 TCP 連線快照。 */
    data class ActiveTcpSnapshot(
        val uid: Int?,
        val local: InetSocketAddress,
        val remote: InetSocketAddress,
        val hostname: String?,
        val createdAt: Long,
    )

    /** 列出目前追蹤中的活躍 TCP 連線（依建立時間由新到舊）。 */
    fun activeConnections(): List<ActiveTcpSnapshot> {
        val now = clock()
        return synchronized(cacheLock) {
            activeTcp.entries.removeAll { now - it.value.createdAt >= ACTIVE_TTL_MS }
            activeTcp.values
                .map { flow ->
                    ActiveTcpSnapshot(
                        uid = flow.uid,
                        local = flow.info.source,
                        remote = flow.info.destination,
                        hostname = flow.hostname,
                        createdAt = flow.createdAt,
                    )
                }
                .sortedByDescending { it.createdAt }
        }
    }

    /** 手動標記單條活躍 TCP 連線為待切斷（下一個封包經過時雙向送出精確序號的 RST）。 */
    fun cutConnection(local: InetSocketAddress, remote: InetSocketAddress): Boolean {
        val key = FlowKey(L4Protocol.Tcp, local, remote)
        return synchronized(cacheLock) {
            val existed = activeTcp.remove(key) != null
            doomed += key
            killPending = true
            existed
        }
    }

    /** 新的隧道：舊連線與舊在途 DNS 都已不存在，清空所有狀態。 */
    fun reset() {
        dnsPacer.clear()
        synchronized(cacheLock) {
            decisions.clear()
            activeTcp.clear()
            doomed.clear()
            killPending = false
            epoch++
        }
    }

    /** 從虛擬網卡來的既有連線封包：若屬於待切斷的連線，回 RST 給應用、送 RST 給 hev，並丟棄原封包。 */
    private fun cutFromClient(packet: ByteArray, length: Int, writer: PacketWriter): Boolean {
        val info = IpPacketParser.tryParse(packet, length) ?: return false
        if (info.protocol != L4Protocol.Tcp) return false
        val key = FlowKey(L4Protocol.Tcp, info.source, info.destination)
        if (!takeDoomed(key)) return false
        val ack = tcpAcknowledgment(packet, length) ?: return false
        writer.write(IpPacketBuilder.tcpReset(info.destination, info.source, info.tcpSequence, sequence = ack))
        toHev?.write(IpPacketBuilder.tcpReset(info.source, info.destination, ack, sequence = info.tcpSequence))
        counters.connectionsCut.incrementAndGet()
        return true
    }

    /** 從 hev 來的封包（伺服器 → 應用）：若屬於待切斷的連線，同樣雙向 RST 並丟棄。 */
    private fun cutFromHev(packet: ByteArray, length: Int, writer: PacketWriter): Boolean {
        val info = IpPacketParser.tryParse(packet, length) ?: return false
        if (info.protocol != L4Protocol.Tcp) return false
        val key = FlowKey(L4Protocol.Tcp, info.destination, info.source)
        if (!takeDoomed(key)) return false
        val ack = tcpAcknowledgment(packet, length) ?: return false
        writer.write(IpPacketBuilder.tcpReset(info.source, info.destination, ack, sequence = info.tcpSequence))
        toHev?.write(IpPacketBuilder.tcpReset(info.destination, info.source, info.tcpSequence, sequence = ack))
        counters.connectionsCut.incrementAndGet()
        return true
    }

    private fun takeDoomed(key: FlowKey): Boolean = synchronized(cacheLock) {
        val removed = doomed.remove(key)
        if (doomed.isEmpty()) killPending = false
        removed
    }

    fun hostnameFor(address: InetAddress): String? = synchronized(namesLock) { names[address] }

    /**
     * 處理一個從虛擬網卡讀到的封包。
     * @return true 表示交給 hev 轉發；false 表示丟棄（必要時已經透過 [writer] 回送拒絕封包）。
     */
    fun onTunPacket(packet: ByteArray, length: Int, writer: PacketWriter): Boolean {
        counters.packets.incrementAndGet()
        if (isEstablishedTcp(packet, length)) {
            if (killPending && cutFromClient(packet, length, writer)) return false
            counters.forwarded.incrementAndGet()
            return true
        }
        if (isRecentForwardedUdp(packet, length)) {
            counters.udpFastPath.incrementAndGet()
            counters.forwarded.incrementAndGet()
            return true
        }
        val info = IpPacketParser.tryParse(packet, length) ?: run {
            // 無法解析（分片、未知協定）時交給 hev 自行處理，避免誤殺。
            counters.forwarded.incrementAndGet()
            return true
        }
        when (info.protocol) {
            L4Protocol.Tcp -> {
                if (!info.isConnectionAttempt) {
                    counters.forwarded.incrementAndGet()
                    return true
                }
                if (!admitFakeIp(packet, length, info, writer)) return false
                // DNS over TCP 到其他伺服器：拒絕，讓解析器改用（會被改寫的）UDP。
                if (info.destination.port == dnsPort && info.destination.address != dnsAddress && redirectDns()) {
                    counters.dnsBypassBlocked.incrementAndGet()
                    reject(packet, length, info, writer)
                    return false
                }
            }
            L4Protocol.Udp -> {
                if (info.destination.port == dnsPort && info.destination.address == dnsAddress) {
                    counters.forwarded.incrementAndGet()
                    return forwardDnsQuery(packet, length, clock())
                }
                if (info.destination.port == dnsPort && redirectDns()) {
                    if (dnsRedirect.redirectQuery(packet, length)) {
                        counters.dnsRedirected.incrementAndGet()
                        counters.forwarded.incrementAndGet()
                        return forwardDnsQuery(packet, length, clock())
                    }
                    // IPv6 的 DNS 伺服器無法改寫到 IPv4 的映射 DNS：回 ICMP 不可達，解析器會改用其他伺服器。
                    counters.dnsBypassBlocked.incrementAndGet()
                    reject(packet, length, info, writer)
                    return false
                }
                if (!admitFakeIp(packet, length, info, writer)) return false
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
            lastUid = uid
            if (uid == null) counters.unknownOwner.incrementAndGet()
            val fresh = decide(uid, info, hostnameFor(info.destination.address))
            remember(key, fresh, now, info.protocol)
            when (fresh) {
                ForwardingDecision.Forward -> onForwarded(info, uid)
                ForwardingDecision.Blocklisted -> onBlocklisted(info, uid)
                else -> onBlocked(info, uid, fresh == ForwardingDecision.BlockAndAsk)
            }
            fresh
        }

        if (decision == ForwardingDecision.Forward) {
            if (info.protocol == L4Protocol.Udp) rememberForwardedUdp(packet, length, now)
            if (info.protocol == L4Protocol.Tcp && cached == null) {
                val hostname = hostnameFor(info.destination.address)
                synchronized(cacheLock) { activeTcp[key] = ActiveFlow(lastUid, info, hostname, now) }
            }
            counters.forwarded.incrementAndGet()
            return true
        }
        counters.blocked.incrementAndGet()
        reject(packet, length, info, writer)
        return false
    }

    private fun isRecentForwardedUdp(packet: ByteArray, length: Int): Boolean {
        val keyLength = udpKeyLength(packet, length)
        if (keyLength == 0) return false
        val currentEpoch = epoch
        for (slot in 0 until UDP_FAST_SLOTS) {
            if (udpKeyLengths[slot] != keyLength || udpEpochs[slot] != currentEpoch) continue
            if (!udpKeyEquals(packet, keyLength, slot * UDP_KEY_MAX)) continue
            if (clock() >= udpUntil[slot]) {
                udpKeyLengths[slot] = 0
                return false
            }
            return true
        }
        return false
    }

    private fun rememberForwardedUdp(packet: ByteArray, length: Int, now: Long) {
        val keyLength = udpKeyLength(packet, length)
        if (keyLength == 0) return
        val currentEpoch = epoch
        val deadline = now + UDP_FAST_PATH_MS
        for (slot in 0 until UDP_FAST_SLOTS) {
            if (udpKeyLengths[slot] == keyLength && udpEpochs[slot] == currentEpoch && udpKeyEquals(packet, keyLength, slot * UDP_KEY_MAX)) {
                udpUntil[slot] = deadline
                return
            }
        }
        val slot = udpNextSlot
        udpNextSlot = (slot + 1) % UDP_FAST_SLOTS
        copyUdpKey(packet, keyLength, slot * UDP_KEY_MAX)
        udpKeyLengths[slot] = keyLength
        udpEpochs[slot] = currentEpoch
        // 快速路徑期限較短：到期後走完整路徑，順便刷新五元組緩存的滑動期限。
        udpUntil[slot] = deadline
    }

    /** IPv4（無分片）回傳 12（兩個位址 + 兩個端口），IPv6（無擴展頭）回傳 36；其他回傳 0。 */
    private fun udpKeyLength(packet: ByteArray, length: Int): Int {
        if (length < 1) return 0
        return when ((packet[0].toInt() ushr 4) and 0x0F) {
            4 -> {
                if (length < 28 || (packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return 0
                if ((readU16(packet, 6) and 0x3FFF) != 0) return 0
                val ihl = (packet[0].toInt() and 0x0F) * 4
                if (ihl < 20 || ihl + 8 > length) 0 else 12
            }
            6 -> if (length >= 48 && (packet[6].toInt() and 0xFF) == PROTOCOL_UDP) 36 else 0
            else -> 0
        }
    }

    private fun udpKeyEquals(packet: ByteArray, keyLength: Int, baseOffset: Int): Boolean {
        var k = baseOffset
        forEachUdpKeyByte(packet, keyLength) { index -> if (packet[index] != udpKeys[k++]) return false }
        return true
    }

    private fun copyUdpKey(packet: ByteArray, keyLength: Int, baseOffset: Int) {
        var k = baseOffset
        forEachUdpKeyByte(packet, keyLength) { index -> udpKeys[k++] = packet[index] }
    }

    private inline fun forEachUdpKeyByte(packet: ByteArray, keyLength: Int, action: (Int) -> Unit) {
        if (keyLength == 12) {
            for (i in 12 until 20) action(i)
            val ihl = (packet[0].toInt() and 0x0F) * 4
            for (i in ihl until ihl + 4) action(i)
        } else {
            for (i in 8 until 40) action(i)
            for (i in 40 until 44) action(i)
        }
    }

    /** 只在虛擬網卡讀取執行緒上使用：最近一次查詢到的 UID（記錄到已轉發連線）。 */
    private var lastUid: Int? = null

    /**
     * 處理一個 hev 寫回虛擬網卡的封包：監聽映射 DNS 的回覆、還原被改寫的 DNS，並切斷待切斷的連線。
     * @return false 表示丟棄這個封包（已經另外送出 RST）。
     */
    fun onHevPacket(packet: ByteArray, length: Int, writer: PacketWriter? = null): Boolean {
        if (killPending && writer != null && cutFromHev(packet, length, writer)) return false
        return !handleHevDns(packet, length)
    }

    /** @return true 表示這是映射重放的回覆（不寫回虛擬網卡）。 */
    private fun handleHevDns(packet: ByteArray, length: Int): Boolean {
        if (!isUdpFromPort(packet, length, dnsPort)) return false
        val info = IpPacketParser.tryParse(packet, length) ?: return false
        if (info.protocol != L4Protocol.Udp || info.source.address != dnsAddress) return false
        // 一個映射 DNS 回覆：讓出在途名額（下一個查詢由放行執行緒依間隔送出）。
        dnsPacer.onResponse(packet, length, clock())
        val end = (info.payloadOffset + info.payloadLength).coerceAtMost(length)
        val answers = DnsAnswers.parseA(packet, info.payloadOffset, end)
        if (answers.isNotEmpty()) {
            synchronized(namesLock) {
                answers.forEach { (name, address) -> names[address] = name }
            }
            answers.forEach { (name, address) ->
                if (!fakeIps.record(name, address)) {
                    com.networkcontrol.android.kotlin.platform.AppLog.w("forwarding", "mapping replay mismatch; remaining addresses dropped")
                }
            }
            counters.dnsMapped.addAndGet(answers.size.toLong())
        }
        if (info.destination.port == FakeIpMappings.REPLAY_PORT) {
            // 重放的查詢沒有得到位址：後面的位址順序已無法保證
            if (answers.isEmpty()) fakeIps.abort()
            return true
        }
        // 被改寫的查詢：把回覆的來源改回應用原本詢問的伺服器（就地修改，轉送層隨後寫回虛擬網卡）。
        dnsRedirect.restoreReply(packet, length)
        return false
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

    /**
     * 新連線的目的地是假位址時，確認 hev 認得它（見 [FakeIpMappings]）。
     * @return false 表示不轉發：重放中尚未恢復的位址直接丟棄（應用會重送），hev 不認得的位址回拒絕。
     */
    private fun admitFakeIp(packet: ByteArray, length: Int, info: PacketInfo, writer: PacketWriter): Boolean =
        when (fakeIps.state(info.destination.address)) {
            FakeIpMappings.State.Pending -> {
                counters.fakeIpHeld.incrementAndGet()
                false
            }
            FakeIpMappings.State.Unknown -> {
                counters.fakeIpRejected.incrementAndGet()
                reject(packet, length, info, writer)
                false
            }
            else -> true
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

        /** 已轉發 TCP 連線的追蹤期限；更久的長連線不再於規則改變時重新判決。 */
        const val ACTIVE_TTL_MS = 6 * 60 * 60 * 1000L

        private fun tcpAcknowledgment(packet: ByteArray, length: Int): Long? {
            val offset = when ((packet[0].toInt() ushr 4) and 0x0F) {
                4 -> (packet[0].toInt() and 0x0F) * 4
                6 -> 40
                else -> return null
            }
            if (offset + 12 > length) return null
            return ((packet[offset + 8].toLong() and 0xFF) shl 24) or ((packet[offset + 9].toLong() and 0xFF) shl 16) or
                ((packet[offset + 10].toLong() and 0xFF) shl 8) or (packet[offset + 11].toLong() and 0xFF)
        }

        /** UDP 快速路徑的有效期；到期後重新走完整路徑（含緩存期限刷新）。 */
        const val UDP_FAST_PATH_MS = 2_000L

        private const val UDP_FAST_SLOTS = 8
        private const val UDP_KEY_MAX = 36

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
 *
 * 黑洞模式只能按應用路由，帶域名 / 端口條件的規則無法生效；轉發模式逐連線判決，
 * 所以這類規則可以在這裡套用（見 [decide] 的 `flowRule`）。
 */
object ForwardingPolicy {
    /**
     * @param flowRule 計算「帶連線條件的規則」對這條連線的判決；沒有符合時回傳 null。
     *   只在可以按規則判決時才會呼叫（專注模式、全部阻止、停用、系統 UID 時不呼叫）。
     */
    fun decide(
        plan: com.networkcontrol.android.kotlin.core.model.VpnRoutingPlan,
        uid: Int?,
        blocklisted: () -> Boolean = { false },
        flowRule: () -> com.networkcontrol.android.kotlin.core.model.VerdictAction?,
    ): ForwardingDecision {
        val base = decide(plan, uid)
        // 系統核心 UID 不套用名單：誤攔會影響整機（例如 DNS、時間同步）。
        if (uid != null && VpnRoutingPlanner.isSystemUid(uid)) return base
        when (plan.mode) {
            com.networkcontrol.android.kotlin.core.model.FilterMode.BlockAll,
            com.networkcontrol.android.kotlin.core.model.FilterMode.Disabled,
            -> return base
            else -> Unit
        }
        val reason = uid?.let { plan.find(it)?.reason }
        if (reason == com.networkcontrol.android.kotlin.core.model.AppRoutingReason.Self) return base
        // 1. 使用者的條件規則（域名 / 端口…）優先；專注模式的放行應用不受規則影響。
        if (uid != null && reason != com.networkcontrol.android.kotlin.core.model.AppRoutingReason.Focus) {
            when (flowRule()) {
                com.networkcontrol.android.kotlin.core.model.VerdictAction.Allow -> return ForwardingDecision.Forward
                com.networkcontrol.android.kotlin.core.model.VerdictAction.Deny -> return ForwardingDecision.Block
                null -> Unit
            }
        }
        // 2. 攔截列表：對本來會放行或詢問的連線生效（廣告域名不必詢問）。
        if (base != ForwardingDecision.Block && blocklisted()) return ForwardingDecision.Blocklisted
        // 3. 路由計畫。
        return base
    }

    /**
     * 只回傳帶連線條件的規則的判決：應用級規則已經反映在路由計畫中（含可信應用、篡改檢測）。
     * 第一條符合的規則是應用級規則時回傳 null，交給路由計畫。
     */
    fun conditionalAction(
        ruleSet: com.networkcontrol.android.kotlin.core.rules.RuleSet,
        flow: com.networkcontrol.android.kotlin.core.model.FlowInfo,
        domains: com.networkcontrol.android.kotlin.core.model.DomainCandidates,
        zone: com.networkcontrol.android.kotlin.core.model.NetworkZone,
        network: com.networkcontrol.android.kotlin.core.model.NetworkContext,
    ): com.networkcontrol.android.kotlin.core.model.VerdictAction? {
        if (!ruleSet.hasFlowConditions) return null
        val rule = ruleSet.evaluate(flow, flow.app, domains, zone, network).rule ?: return null
        return if (VpnRoutingPlanner.isEnforceable(rule)) null else rule.action
    }

    fun decide(plan: com.networkcontrol.android.kotlin.core.model.VpnRoutingPlan, uid: Int?): ForwardingDecision {
        val mode = plan.mode
        // 查不到擁有者（getConnectionOwnerUid 失敗）時無法詢問：
        // 「全部阻止」與「白名單」（預設拒絕）一律拒絕；其他模式放行，避免誤殺系統元件的連線。
        if (uid == null) {
            return when (mode) {
                com.networkcontrol.android.kotlin.core.model.FilterMode.BlockAll,
                com.networkcontrol.android.kotlin.core.model.FilterMode.Whitelist,
                -> ForwardingDecision.Block
                else -> ForwardingDecision.Forward
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
