package com.networkcontrol.android.kotlin.engine.vpn

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.networkcontrol.android.kotlin.platform.AppLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 在 VPN 虛擬網卡與 hev 之間轉送封包：
 *
 * ```
 * 虛擬網卡 ──讀──▶ ForwardingPacketFilter ──允許──▶ socketpair ──▶ hev（lwIP → SOCKS5）
 * 虛擬網卡 ◀──寫── （監聽映射 DNS 回覆）    ◀──────── socketpair ◀── hev
 * ```
 *
 * hev 對非 TUN 的 fd 會退回一般 read/write（`TUNGETIFF` 失敗即不使用 vnet header），
 * 而 AF_UNIX SOCK_SEQPACKET 保留封包邊界，所以 hev 可以把 socketpair 當成虛擬網卡使用。
 */
class ForwardingRelay(
    private val tun: ParcelFileDescriptor,
    private val filter: ForwardingPacketFilter,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val pair: Array<ParcelFileDescriptor> = ParcelFileDescriptor.createReliableSocketPair()
    private val relaySide = pair[0]

    /** 交給 hev 的一端；hev 停止前必須保持開啟。 */
    val hevSide: ParcelFileDescriptor = pair[1]

    private val tunInput = FileInputStream(tun.fileDescriptor)
    private val tunOutput = FileOutputStream(tun.fileDescriptor)
    private val relayInput = FileInputStream(relaySide.fileDescriptor)
    private val relayOutput = FileOutputStream(relaySide.fileDescriptor)

    @Volatile
    private var stopped = false

    private val tunWriter = object : PacketWriter {
        override fun write(packet: ByteArray) {
            writeTun(packet, packet.size)
        }
    }

    private var upstream: Thread? = null
    private var downstream: Thread? = null

    init {
        listOf(relaySide, hevSide).forEach { pfd ->
            runCatching {
                Os.setsockoptInt(pfd.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, SOCKET_BUFFER)
                Os.setsockoptInt(pfd.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, SOCKET_BUFFER)
            }
        }
    }

    fun start() {
        upstream = Thread({ pumpUpstream() }, "NetworkControlKT.FwdUp").apply {
            isDaemon = true
            start()
        }
        downstream = Thread({ pumpDownstream() }, "NetworkControlKT.FwdDown").apply {
            isDaemon = true
            start()
        }
    }

    private fun pumpUpstream() {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (!stopped) {
                val read = tunInput.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (filter.onTunPacket(buffer, read, tunWriter)) {
                    relayOutput.write(buffer, 0, read)
                }
            }
        } catch (ex: IOException) {
            if (!stopped) onError(ex)
        } catch (ex: Throwable) {
            if (!stopped) onError(ex)
        }
    }

    private fun pumpDownstream() {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (!stopped) {
                val read = relayInput.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                runCatching { filter.onHevPacket(buffer, read) }
                writeTun(buffer, read)
            }
        } catch (ex: IOException) {
            if (!stopped) onError(ex)
        } catch (ex: Throwable) {
            if (!stopped) onError(ex)
        }
    }

    private fun writeTun(packet: ByteArray, length: Int) {
        if (stopped) return
        try {
            synchronized(tunOutput) { tunOutput.write(packet, 0, length) }
        } catch (ex: IOException) {
            if (!stopped) AppLog.w("forwarding", "tun write failed: ${ex.message}")
        }
    }

    /**
     * 先停 hev（呼叫端負責），再關閉轉送；本方法可以重複呼叫。
     * 讀取虛擬網卡的執行緒會在呼叫端關閉虛擬網卡 fd 後結束，這裡不等待它。
     */
    override fun close() {
        if (stopped) return
        stopped = true
        runCatching { relaySide.close() }
        runCatching { hevSide.close() }
        downstream?.let { thread ->
            if (Thread.currentThread() !== thread) runCatching { thread.join(500) }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 65_536
        private const val SOCKET_BUFFER = 1 shl 20
    }
}
