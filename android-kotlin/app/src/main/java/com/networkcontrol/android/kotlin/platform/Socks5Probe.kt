package com.networkcontrol.android.kotlin.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 簡單的 SOCKS5 探針：握手後嘗試 CONNECT 一個外部主機。
 * 成功回傳 null，失敗回傳可直接顯示的錯誤訊息。
 */
object Socks5Probe {
    suspend fun test(
        host: String,
        port: Int,
        targetHost: String = "www.gstatic.com",
        targetPort: Int = 443,
    ): String? = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext "empty host"
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 5000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()

                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val method = readN(input, 2)
                if (method[0] != 0x05.toByte() || method[1] != 0x00.toByte()) {
                    return@use "auth rejected (${method[1]})"
                }

                val target = targetHost.toByteArray(Charsets.UTF_8)
                val request = ByteArray(5 + target.size + 2)
                request[0] = 0x05
                request[1] = 0x01
                request[2] = 0x00
                request[3] = 0x03
                request[4] = target.size.toByte()
                target.copyInto(request, 5)
                request[5 + target.size] = (targetPort shr 8).toByte()
                request[6 + target.size] = targetPort.toByte()
                out.write(request)
                out.flush()

                val head = readN(input, 4)
                if (head[0] != 0x05.toByte() || head[1] != 0x00.toByte()) {
                    return@use "connect failed (${head[1]})"
                }
                when (head[3].toInt() and 0xFF) {
                    0x01 -> readN(input, 4 + 2)
                    0x03 -> {
                        val length = readN(input, 1)[0].toInt() and 0xFF
                        readN(input, length + 2)
                    }
                    0x04 -> readN(input, 16 + 2)
                    else -> return@use "bad address type (${head[3]})"
                }
                null
            }
        }.getOrElse { error ->
            error.message ?: error.javaClass.simpleName
        }
    }

    /**
     * 只做 SOCKS5 方法協商（不連外網），用於轉發模式的週期健康檢查。
     * 成功回傳 null，失敗回傳錯誤訊息。
     */
    suspend fun handshake(host: String, port: Int, timeoutMs: Int = 3000): String? = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext "empty host"
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.getOutputStream().apply {
                    write(byteArrayOf(0x05, 0x01, 0x00))
                    flush()
                }
                val method = readN(socket.getInputStream(), 2)
                if (method[0] != 0x05.toByte() || method[1] != 0x00.toByte()) {
                    "auth rejected (${method[1]})"
                } else {
                    null
                }
            }
        }.getOrElse { error -> error.message ?: error.javaClass.simpleName }
    }

    private fun readN(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) throw EOFException("proxy closed the connection")
            offset += read
        }
        return buffer
    }
}
