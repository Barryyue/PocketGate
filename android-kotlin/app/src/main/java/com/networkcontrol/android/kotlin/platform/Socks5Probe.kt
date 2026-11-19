package com.networkcontrol.android.kotlin.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 三段式 SOCKS5 代理診斷結果：清楚區分本地埠連通、握手認證與經代理出網三階段。
 */
data class ProxyProbeReport(
    val tcpOk: Boolean,
    val tcpMessage: String,
    val authOk: Boolean,
    val authMessage: String,
    val outboundOk: Boolean,
    val outboundMessage: String,
    val latencyMs: Long,
) {
    val allOk: Boolean get() = tcpOk && authOk && outboundOk
}

/**
 * 簡單的 SOCKS5 探針：握手後嘗試 CONNECT 一個外部主機。
 * 成功回傳 null，失敗回傳可直接顯示的錯誤訊息。
 */
object Socks5Probe {
    /** 三段式精準診斷：① 本地埠 TCP 連通 → ② SOCKS5 握手/認證 → ③ 經代理連外網。 */
    suspend fun diagnose(
        host: String,
        port: Int,
        targetHost: String = "www.gstatic.com",
        targetPort: Int = 443,
        username: String? = null,
        password: String? = null,
    ): ProxyProbeReport = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        fun elapsedMs(): Long = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        if (host.isBlank()) {
            return@withContext ProxyProbeReport(
                tcpOk = false,
                tcpMessage = "empty host",
                authOk = false,
                authMessage = "—",
                outboundOk = false,
                outboundMessage = "—",
                latencyMs = elapsedMs(),
            )
        }
        val socket = Socket()
        try {
            // 階段 1：本地埠連通
            try {
                socket.connect(InetSocketAddress(host.trim(), port), 3000)
                socket.soTimeout = 5000
            } catch (ex: Exception) {
                return@withContext ProxyProbeReport(
                    tcpOk = false,
                    tcpMessage = ex.message ?: ex.javaClass.simpleName,
                    authOk = false,
                    authMessage = "—",
                    outboundOk = false,
                    outboundMessage = "—",
                    latencyMs = elapsedMs(),
                )
            }
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // 階段 2：SOCKS5 握手與認證
            val authErr = runCatching { negotiate(out, input, username, password) }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            if (authErr != null) {
                return@withContext ProxyProbeReport(
                    tcpOk = true,
                    tcpMessage = "OK",
                    authOk = false,
                    authMessage = authErr,
                    outboundOk = false,
                    outboundMessage = "—",
                    latencyMs = elapsedMs(),
                )
            }

            // 階段 3：經代理 CONNECT 外網
            val outboundErr = runCatching {
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
                    return@runCatching "connect failed (${head[1]})"
                }
                when (head[3].toInt() and 0xFF) {
                    0x01 -> readN(input, 4 + 2)
                    0x03 -> {
                        val length = readN(input, 1)[0].toInt() and 0xFF
                        readN(input, length + 2)
                    }
                    0x04 -> readN(input, 16 + 2)
                    else -> return@runCatching "bad address type (${head[3]})"
                }
                null
            }.getOrElse { it.message ?: it.javaClass.simpleName }

            ProxyProbeReport(
                tcpOk = true,
                tcpMessage = "OK",
                authOk = true,
                authMessage = "OK",
                outboundOk = outboundErr == null,
                outboundMessage = outboundErr ?: "OK",
                latencyMs = elapsedMs(),
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    suspend fun test(
        host: String,
        port: Int,
        targetHost: String = "www.gstatic.com",
        targetPort: Int = 443,
        username: String? = null,
        password: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext "empty host"
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 5000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()

                negotiate(out, input, username, password)?.let { return@use it }

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
    suspend fun handshake(
        host: String,
        port: Int,
        timeoutMs: Int = 3000,
        username: String? = null,
        password: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext "empty host"
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                negotiate(socket.getOutputStream(), socket.getInputStream(), username, password)
            }
        }.getOrElse { error -> error.message ?: error.javaClass.simpleName }
    }

    /**
     * 方法協商：有帳號密碼時同時提供「無驗證」與「帳號密碼」（RFC 1929），由代理選擇。
     * 成功回傳 null，失敗回傳錯誤訊息。
     */
    internal fun negotiate(out: java.io.OutputStream, input: InputStream, username: String?, password: String?): String? {
        val withAuth = !username.isNullOrEmpty()
        out.write(if (withAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val method = readN(input, 2)
        if (method[0] != 0x05.toByte()) return "not a SOCKS5 proxy"
        return when (method[1].toInt() and 0xFF) {
            0x00 -> null
            0x02 -> {
                if (!withAuth) return "proxy requires username / password"
                val user = username!!.toByteArray(Charsets.UTF_8)
                val pass = password.orEmpty().toByteArray(Charsets.UTF_8)
                if (user.size > 255 || pass.size > 255) return "username / password too long"
                out.write(byteArrayOf(0x01, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                out.flush()
                val status = readN(input, 2)
                if (status[1].toInt() != 0) "wrong username / password" else null
            }
            0xFF -> if (withAuth) "auth rejected" else "proxy requires username / password"
            else -> "unsupported auth method (${method[1]})"
        }
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
