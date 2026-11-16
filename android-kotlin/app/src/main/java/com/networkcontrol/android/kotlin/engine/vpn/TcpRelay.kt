package com.networkcontrol.android.kotlin.engine.vpn

import com.networkcontrol.android.kotlin.platform.AppLog
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 以單一執行緒（NIO Selector）轉送所有 TCP 連線對，取代「每條連線兩個執行緒」：
 * 連線數增加時只多佔兩個 32 KB 緩衝區，不再多佔執行緒與堆疊。
 *
 * - 背壓：某方向的緩衝區滿了就停止讀來源端，等目的端可寫再繼續；
 * - 半關閉：一端送完（EOF）且緩衝區清空後，對另一端 `shutdownOutput()`，另一方向照常轉送；
 * - 兩個方向都結束、任一端出錯、或半關閉後閒置超過 [halfCloseIdleMs] 時關閉整對連線。
 */
class TcpRelay(
    private val onClosed: () -> Unit = {},
    private val bufferSize: Int = 32 * 1024,
    private val halfCloseIdleMs: Long = 120_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val selector: Selector = Selector.open()
    private val pending = ConcurrentLinkedQueue<Pipe>()
    private val pipes = HashSet<Pipe>()

    @Volatile
    private var closed = false

    @Volatile
    var activePipes = 0
        private set

    private val thread = Thread({ loop() }, "NetworkControlKT.TcpRelay").apply {
        isDaemon = true
        start()
    }

    /** 接手一對已連線的通道；之後由轉送層負責關閉它們。 */
    fun register(client: SocketChannel, remote: SocketChannel) {
        if (closed) {
            runCatching { client.close() }
            runCatching { remote.close() }
            onClosed()
            return
        }
        pending += Pipe(client, remote)
        selector.wakeup()
    }

    private inner class Pipe(val a: SocketChannel, val b: SocketChannel) {
        /** a → b 方向的資料（寫入模式：position 為已填入的量）。 */
        val ab: ByteBuffer = ByteBuffer.allocate(bufferSize)
        val ba: ByteBuffer = ByteBuffer.allocate(bufferSize)
        var aEof = false
        var bEof = false
        var abShut = false
        var baShut = false
        var keyA: SelectionKey? = null
        var keyB: SelectionKey? = null
        var lastActivity = clock()
        var done = false

        fun open() {
            a.configureBlocking(false)
            b.configureBlocking(false)
            keyA = a.register(selector, SelectionKey.OP_READ, Endpoint(this, true))
            keyB = b.register(selector, SelectionKey.OP_READ, Endpoint(this, false))
            pipes += this
            activePipes = pipes.size
        }

        fun service(fromA: Boolean, key: SelectionKey) {
            lastActivity = clock()
            if (key.isReadable) {
                if (fromA) aEof = read(a, ab) || aEof else bEof = read(b, ba) || bEof
            }
            // 讀完後立刻嘗試寫往對端，減少一次 select 往返。
            flush(ab, b)
            flush(ba, a)
            if (aEof && ab.position() == 0 && !abShut) {
                abShut = true
                runCatching { b.shutdownOutput() }
            }
            if (bEof && ba.position() == 0 && !baShut) {
                baShut = true
                runCatching { a.shutdownOutput() }
            }
            if (abShut && baShut) {
                close()
                return
            }
            updateInterest()
        }

        /** @return true 表示讀到 EOF。 */
        private fun read(channel: SocketChannel, buffer: ByteBuffer): Boolean {
            if (!buffer.hasRemaining()) return false
            return channel.read(buffer) < 0
        }

        private fun flush(buffer: ByteBuffer, target: SocketChannel) {
            if (buffer.position() == 0) return
            buffer.flip()
            try {
                target.write(buffer)
            } finally {
                buffer.compact()
            }
        }

        private fun updateInterest() {
            keyA?.takeIf { it.isValid }?.interestOps(
                (if (!aEof && ab.hasRemaining()) SelectionKey.OP_READ else 0) or
                    (if (ba.position() > 0) SelectionKey.OP_WRITE else 0),
            )
            keyB?.takeIf { it.isValid }?.interestOps(
                (if (!bEof && ba.hasRemaining()) SelectionKey.OP_READ else 0) or
                    (if (ab.position() > 0) SelectionKey.OP_WRITE else 0),
            )
        }

        fun close() {
            if (done) return
            done = true
            keyA?.cancel()
            keyB?.cancel()
            runCatching { a.close() }
            runCatching { b.close() }
            pipes -= this
            activePipes = pipes.size
            onClosed()
        }

        val halfClosed: Boolean get() = aEof || bEof
    }

    private class Endpoint(val pipe: TcpRelay.Pipe, val isA: Boolean)

    private fun loop() {
        var lastSweep = clock()
        try {
            while (!closed) {
                selector.select(SWEEP_INTERVAL_MS)
                while (true) {
                    val pipe = pending.poll() ?: break
                    try {
                        pipe.open()
                    } catch (ex: IOException) {
                        pipe.close()
                    }
                }
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    val endpoint = key.attachment() as? Endpoint ?: continue
                    if (!key.isValid) {
                        endpoint.pipe.close()
                        continue
                    }
                    try {
                        endpoint.pipe.service(endpoint.isA, key)
                    } catch (_: IOException) {
                        endpoint.pipe.close()
                    } catch (_: java.nio.channels.CancelledKeyException) {
                        endpoint.pipe.close()
                    }
                }
                val now = clock()
                if (now - lastSweep >= SWEEP_INTERVAL_MS) {
                    lastSweep = now
                    pipes.filter { it.halfClosed && now - it.lastActivity > halfCloseIdleMs }.forEach { it.close() }
                }
            }
        } catch (ex: Exception) {
            if (!closed) AppLog.e("direct", "tcp relay loop failed", ex)
        } finally {
            pipes.toList().forEach { it.close() }
            while (true) {
                val pipe = pending.poll() ?: break
                runCatching { pipe.a.close() }
                runCatching { pipe.b.close() }
                onClosed()
            }
            runCatching { selector.close() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        selector.wakeup()
        if (Thread.currentThread() !== thread) runCatching { thread.join(1_000) }
    }

    private companion object {
        const val SWEEP_INTERVAL_MS = 15_000L
    }
}
