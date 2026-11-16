package com.networkcontrol.android.kotlin.engine.vpn

import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.AppRoutingDecision
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.FlowInfo
import com.networkcontrol.android.kotlin.core.model.FlowOrigin
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.core.model.VpnRoutingPlan
import com.networkcontrol.android.kotlin.engine.apps.AppCatalog
import com.networkcontrol.android.kotlin.engine.apps.ConnectionOwnerResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

class VpnEngineCounters {
    val packets = AtomicLong()
    val unparsed = AtomicLong()
    val attempts = AtomicLong()
    val queueFull = AtomicLong()
    val unknownOwner = AtomicLong()
    val deduplicated = AtomicLong()
    val reported = AtomicLong()
    val dnsAnswered = AtomicLong()
    val systemUid = AtomicLong()
    val resetsSent = AtomicLong()
    val unreachablesSent = AtomicLong()
}

class BlockedFlowReporter(
    private val owners: ConnectionOwnerResolver,
    private val catalog: AppCatalog,
    private val apps: com.networkcontrol.android.kotlin.core.storage.AppStore,
    private val currentPlan: () -> VpnRoutingPlan,
    private val fakeDns: FakeDnsPool,
    private val counters: VpnEngineCounters,
    private val onFlow: suspend (FlowInfo) -> Unit,
    private val fastFail: Boolean = true,
    /** 轉發模式下由 hev 映射 DNS 還原的域名。 */
    private val extraHostname: (InetAddress) -> String? = { null },
) : PacketSink {
    private val channel = Channel<Queued>(capacity = 512)
    private val recent = HashMap<DedupKey, Long>()
    private val lastFastFail = HashMap<FastFailKey, Long>()
    private val identityCache = HashMap<String, AppIdentity>()
    private var nextFlowId = 0L
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        scope.launch {
            for (queued in channel) {
                runCatching { process(queued) }
            }
        }
    }

    fun stop() {
        channel.close()
    }

    override fun onPacket(packet: ByteArray, length: Int, writer: PacketWriter) {
        counters.packets.incrementAndGet()
        val info = IpPacketParser.tryParse(packet, length)
        if (info == null) {
            counters.unparsed.incrementAndGet()
            return
        }

        if (info.protocol == L4Protocol.Udp &&
            info.destination.port == 53 &&
            info.destination.address == FakeDnsPool.DnsServer
        ) {
            answerDns(packet, info, writer)
            return
        }

        if (!info.isConnectionAttempt) return
        // 快速失敗需要同步知道 UID；查到的結果直接交給背景處理，不再查第二次 Binder。
        val queued = if (fastFail) {
            Queued(info, tryFastFail(packet, length, info, writer), resolved = true)
        } else {
            Queued(info, null, resolved = false)
        }

        counters.attempts.incrementAndGet()
        if (channel.trySend(queued).isFailure) counters.queueFull.incrementAndGet()
    }

    /** 轉發模式：過濾層已經判定拒絕的新連線，交給判決管線記錄 / 詢問。uid 已知時不再查詢。 */
    fun report(info: PacketInfo, uid: Int?) {
        counters.attempts.incrementAndGet()
        if (channel.trySend(Queued(info, uid, resolved = uid != null)).isFailure) counters.queueFull.incrementAndGet()
    }

    private suspend fun process(queued: Queued) {
        val packet = queued.info
        val uid = if (queued.resolved) {
            queued.uid
        } else {
            owners.getOwnerUid(packet.protocol, packet.source, packet.destination)
        }
        if (uid == null) {
            counters.unknownOwner.incrementAndGet()
            return
        }
        if (VpnRoutingPlanner.isSystemUid(uid)) {
            counters.systemUid.incrementAndGet()
            return
        }

        val now = Time.now()
        val key = DedupKey(uid, packet.protocol, packet.destination.address, packet.destination.port)
        val last = recent[key]
        if (last != null && now - last < 5_000) {
            counters.deduplicated.incrementAndGet()
            return
        }
        if (recent.size >= 4096) {
            recent.entries.removeAll { now - it.value >= 5_000 }
            if (recent.size >= 4096) recent.clear()
        }
        recent[key] = now

        val plan = currentPlan()
        val routing = plan.find(uid)
        val packages = catalog.packagesForUid(uid)
        val packageName = routing?.packageName ?: packages.firstOrNull()
        if (packageName == null) {
            counters.unknownOwner.incrementAndGet()
        }
        val installed = packageName?.let { catalog.find(it) }
        val app: AppIdentity? = packageName?.let { name ->
            installed?.let { found ->
                // 鍵包含簽名與更新時間：應用被替換（簽名改變）後必須重新取身分，否則會繞過篡改檢測。
                val cacheKey = "$name|${found.signerSha256.orEmpty()}|${found.lastUpdated}"
                identityCache[cacheKey] ?: run {
                    val identity = runCatching { apps.upsert(catalog.toIdentity(found)) }
                        .getOrElse { catalog.toIdentity(found) }
                    if (identityCache.size >= 1024) identityCache.clear()
                    identityCache[cacheKey] = identity
                    identity
                }
            }
        }
        val flow = FlowInfo(
            flowId = ++nextFlowId,
            direction = Direction.Outbound,
            protocol = packet.protocol,
            local = packet.source,
            remote = packet.destination,
            app = app,
            executablePath = packageName?.let(AppCatalog::appPath) ?: "android-app://uid:$uid",
            engineHostname = fakeDns.tryGetName(packet.destination.address)
                ?: extraHostname(packet.destination.address),
            timestamp = now,
            origin = FlowOrigin.BlockedEvent,
            tcpFlags = packet.tcpFlags,
            tcpSequence = packet.tcpSequence,
        )
        onFlow(flow)
        counters.reported.incrementAndGet()
        com.networkcontrol.android.kotlin.platform.AppLog.d(
            "flow",
            "uid=$uid pkg=${packageName ?: "?"} ${packet.protocol} ${packet.destination} host=${flow.engineHostname}",
        )
    }

    /** 對「直接阻止」的應用立刻回送 RST / ICMP；回傳查到的 UID（查不到為 null）。 */
    private fun tryFastFail(packet: ByteArray, length: Int, info: PacketInfo, writer: PacketWriter): Int? {
        val uid = owners.getOwnerUid(info.protocol, info.source, info.destination) ?: return null
        if (VpnRoutingPlanner.isSystemUid(uid)) return uid
        val routing = currentPlan().find(uid) ?: return uid
        if (routing.decision != AppRoutingDecision.Block) return uid

        val now = Time.now()
        val key = FastFailKey(uid, info.destination.address)
        val last = lastFastFail[key]
        if (last != null && now - last < 1_000) return uid
        if (lastFastFail.size >= 4096) lastFastFail.clear()
        lastFastFail[key] = now

        when (info.protocol) {
            L4Protocol.Tcp -> {
                writer.write(
                    IpPacketBuilder.tcpReset(
                        info.destination,
                        info.source,
                        info.tcpSequence + 1,
                    ),
                )
                counters.resetsSent.incrementAndGet()
            }
            L4Protocol.Udp -> {
                writer.write(
                    IpPacketBuilder.icmpPortUnreachable(
                        info.destination,
                        info.source,
                        packet.copyOfRange(0, length),
                    ),
                )
                counters.unreachablesSent.incrementAndGet()
            }
            else -> Unit
        }
        return uid
    }

    private fun answerDns(packet: ByteArray, info: PacketInfo, writer: PacketWriter) {
        val message = packet.copyOfRange(
            info.payloadOffset,
            (info.payloadOffset + info.payloadLength).coerceAtMost(packet.size),
        )
        val query = DnsWire.tryParseQuery(message) ?: return
        if (query.name.isEmpty()) return
        val address = if (query.type == DnsWire.TYPE_A) fakeDns.allocate(query.name) else null
        val question = message.copyOfRange(12, 12 + query.questionLength)
        val response = DnsWire.buildResponse(query, question, address, 1)
        writer.write(IpPacketBuilder.udp4(info.destination, info.source, response))
        counters.dnsAnswered.incrementAndGet()
        com.networkcontrol.android.kotlin.platform.AppLog.d(
            "dns",
            "answered ${query.name} type=${query.type} -> ${address?.hostAddress ?: "NODATA"}",
        )
    }

    private data class DedupKey(
        val uid: Int,
        val protocol: L4Protocol,
        val address: InetAddress,
        val port: Int,
    )

    private data class FastFailKey(val uid: Int, val address: InetAddress)

    /** [resolved] 為 true 時 [uid] 已經查過（可能為 null，代表查不到擁有者）。 */
    private class Queued(val info: PacketInfo, val uid: Int?, val resolved: Boolean)
}
