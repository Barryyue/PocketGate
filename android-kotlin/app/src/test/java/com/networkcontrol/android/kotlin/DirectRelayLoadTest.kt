package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.engine.vpn.DirectSocksServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DirectRelayLoadTest {
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test
    fun manyConcurrentConnectionsRelayIntactWithoutPerConnectionThreads() {
        // 回音伺服器：讀到半關閉後整批回送
        val echo = ServerSocket(0, 200, loopback)
        thread(isDaemon = true) {
            while (!echo.isClosed) {
                val socket = runCatching { echo.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use { it.getOutputStream().write(it.getInputStream().readBytes()) } }
            }
        }
        val server = DirectSocksServer()
        server.start()
        val connections = 64
        val size = 512 * 1024
        val pool = Executors.newFixedThreadPool(connections)
        try {
            val threadsBefore = Thread.activeCount()
            val results = (0 until connections).map { index ->
                pool.submit<Boolean> {
                    Socket(loopback, server.port).use { socket ->
                        socket.soTimeout = 30_000
                        val input = DataInputStream(socket.getInputStream())
                        val out = socket.getOutputStream()
                        out.write(byteArrayOf(5, 1, 2))
                        input.readFully(ByteArray(2))
                        val user = server.username.toByteArray()
                        val pass = server.password.toByteArray()
                        out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                        input.readFully(ByteArray(2))
                        val port = echo.localPort
                        out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port ushr 8).toByte(), port.toByte()))
                        val reply = ByteArray(10).also { input.readFully(it) }
                        if (reply[1].toInt() != 0) return@submit false
                        val payload = ByteArray(size) { i -> ((i * 31 + index) and 0xFF).toByte() }
                        // 邊寫邊讀，避免雙方緩衝區都滿而互等
                        val writer = thread {
                            out.write(payload)
                            socket.shutdownOutput()
                        }
                        val echoed = input.readBytes()
                        writer.join()
                        MessageDigest.isEqual(echoed, payload)
                    }
                }
            }.map { it.get(60, TimeUnit.SECONDS) }
            assertTrue(results.all { it })
            // 轉送全部結束後計數歸零
            val deadline = System.currentTimeMillis() + 5_000
            while (server.counters.active.get() != 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(0, server.counters.active.get())
            // 握手執行緒會被回收；轉送本身只有一個執行緒，執行緒數不會隨連線數倍增
            assertTrue("threads grew from $threadsBefore to ${Thread.activeCount()}", Thread.activeCount() < threadsBefore + connections * 2)
        } finally {
            pool.shutdownNow()
            server.close()
            echo.close()
        }
    }
}
