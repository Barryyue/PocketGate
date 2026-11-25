package com.networkcontrol.android.kotlin.engine.vpn

import com.networkcontrol.android.kotlin.platform.AppLog
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DirectSocksCounters {
    val accepted = AtomicLong()
    val authFailed = AtomicLong()
    val connectFailed = AtomicLong()
    val active = AtomicInteger()
    val udpAssociations = AtomicLong()
    val rejected = AtomicLong()
    val upstreamProxied = AtomicLong()
    val upstreamUdpProxied = AtomicLong()
}

/** 外部上游 SOCKS5 代理連線參數（供按應用分流出口使用）。 */
data class UpstreamSocks5Config(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
)

/** 單條連線的出口路由決策：本機直連或轉發至外部 SOCKS5 代理。 */
sealed class ExitRoute {
    data object Direct : ExitRoute()
    data class Socks5(val upstream: UpstreamSocks5Config) : ExitRoute()
}

/**
 * 暫存由 [ForwardingPacketFilter] 剛放行的連線目標 -> 出口路由對應表，
 * 讓 [DirectSocksServer] 能在同一條連線建立時依應用包名決定走直連或外部 SOCKS5。
 */
class SplitExitRouter(
    private val ttlMs: Long = 15_000L,
    private val maxEntries: Int = 4096,
) {
    private data class Entry(val route: ExitRoute, val recordedAt: Long)

    private val routes = ConcurrentHashMap<String, Entry>()

    fun record(targetHostOrIp: String, port: Int, route: ExitRoute, nowMs: Long = System.currentTimeMillis()) {
        val key = keyOf(targetHostOrIp, port) ?: return
        if (routes.size >= maxEntries) {
            routes.entries.removeIf { nowMs - it.value.recordedAt > ttlMs }
        }
        routes[key] = Entry(route, nowMs)
    }

    fun resolve(targetHostOrIp: String?, port: Int, nowMs: Long = System.currentTimeMillis()): ExitRoute {
        val key = keyOf(targetHostOrIp, port) ?: return ExitRoute.Direct
        val entry = routes[key] ?: return ExitRoute.Direct
        if (nowMs - entry.recordedAt > ttlMs) {
            routes.remove(key, entry)
            return ExitRoute.Direct
        }
        return entry.route
    }

    fun clear() {
        routes.clear()
    }

    private fun keyOf(host: String?, port: Int): String? {
        val clean = host?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return "$clean:$port"
    }
}

/**
 * 內建的「直連／分流出口」：只監聽 127.0.0.1 的 SOCKS5 伺服器，hev 把已經過防火牆判決的連線交給它，
 * 由它依 [routeFor] 決定以本應用身分直連目標，或轉發至外部 SOCKS5 代理。
 *
 * 安全：本機其他應用也能連 127.0.0.1，如果不驗證，它們就能借這個出口繞過防火牆。
 * 因此一律要求 RFC 1929 帳號密碼（每次啟動隨機產生，只寫進 hev 的設定檔），拒絕無驗證。
 *
 * 支援 CONNECT 與 UDP ASSOCIATE；位址可以是 IPv4、IPv6 或域名（hev 的 mapdns 會送域名）。
 */
class DirectSocksServer(
    private val resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    /** 把對外 socket 綁到實體網路（不綁的話，系統可能把本應用的查詢 / 連線導向 VPN 的 DNS）。 */
    private val bindSocket: (Socket) -> Unit = {},
    private val bindDatagram: (DatagramSocket) -> Unit = {},
    private val connectTimeoutMs: Int = 10_000,
    private val maxThreads: Int = 768,
    /** 按連線請求決定出口（預設直連；啟用按應用分流時可回傳外部 SOCKS5）。 */
    private val routeFor: (Socks5Request) -> ExitRoute = { ExitRoute.Direct },
) : AutoCloseable {
    val username: String = randomToken()
    val password: String = randomToken()
    val counters = DirectSocksCounters()

    private val server: ServerSocketChannel = ServerSocketChannel.open().apply {
        bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 256)
    }
    val port: Int = (server.localAddress as InetSocketAddress).port

    /** 握手完成後的 TCP 轉送：單一執行緒處理所有連線（見 [TcpRelay]）。 */
    private val relay = TcpRelay(onClosed = { counters.active.decrementAndGet() })

    private val threadIds = AtomicInteger()
    private val pool = ThreadPoolExecutor(
        0,
        maxThreads,
        30,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { runnable ->
            // 只用於握手與 UDP 關聯；TCP 資料由 TcpRelay 的單一執行緒轉送。
            Thread(null, runnable, "NetworkControlKT.Direct-${threadIds.incrementAndGet()}", 256 * 1024)
                .apply { isDaemon = true }
        },
    )
    private val sockets = ConcurrentHashMap.newKeySet<java.io.Closeable>()

    @Volatile
    private var closed = false

    fun start() {
        Thread({ acceptLoop() }, "NetworkControlKT.DirectAccept").apply {
            isDaemon = true
            start()
        }
        AppLog.i("direct", "direct exit listening on 127.0.0.1:$port")
    }

    private fun acceptLoop() {
        while (!closed) {
            val channel = try {
                server.accept()
            } catch (ex: IOException) {
                if (!closed) AppLog.w("direct", "accept failed: ${ex.message}")
                break
            }
            counters.accepted.incrementAndGet()
            if (!submit { handle(channel) }) {
                counters.rejected.incrementAndGet()
                runCatching { channel.close() }
            }
        }
    }

    private fun submit(task: () -> Unit): Boolean = try {
        pool.execute(task)
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        false
    }

    private fun handle(channel: SocketChannel) {
        track(channel)
        counters.active.incrementAndGet()
        // 握手在阻塞模式下用串流完成（DataInputStream 不預讀，不會吃掉握手後的資料）。
        val client = channel.socket()
        var handedOff = false
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            if (!negotiate(input, output)) return
            val request = Socks5Wire.readRequest(input) ?: run {
                output.write(Socks5Wire.reply(Socks5Wire.REP_GENERAL_FAILURE))
                return
            }
            when (request.command) {
                Socks5Wire.CMD_CONNECT -> handedOff = connect(channel, output, request)
                Socks5Wire.CMD_UDP_ASSOCIATE -> associate(client, input, output)
                else -> output.write(Socks5Wire.reply(Socks5Wire.REP_COMMAND_NOT_SUPPORTED))
            }
        } catch (_: IOException) {
            // 用戶端或目標斷線：正常結束。
        } catch (ex: RuntimeException) {
            AppLog.w("direct", "session failed: ${ex.message}")
        } catch (ex: Error) {
            AppLog.e("direct", "session crashed", ex)
        } finally {
            if (handedOff) {
                // 交給轉送層：之後由它關閉並遞減計數。
                sockets -= channel
            } else {
                counters.active.decrementAndGet()
                closeQuietly(channel)
            }
        }
    }

    /** 只接受帳號密碼驗證；憑證比對使用固定時間比較。 */
    private fun negotiate(input: DataInputStream, output: OutputStream): Boolean {
        if (input.readUnsignedByte() != Socks5Wire.VERSION) return false
        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        if (methods.none { it.toInt() == Socks5Wire.METHOD_USER_PASS }) {
            AppLog.w("direct", "rejected client without password auth (methods=${methods.joinToString { it.toString() }})")
            output.write(byteArrayOf(Socks5Wire.VERSION.toByte(), 0xFF.toByte()))
            counters.authFailed.incrementAndGet()
            return false
        }
        output.write(byteArrayOf(Socks5Wire.VERSION.toByte(), Socks5Wire.METHOD_USER_PASS.toByte()))
        if (input.readUnsignedByte() != 1) return false
        val user = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        val pass = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        val ok = MessageDigest.isEqual(user, username.toByteArray()) and
            MessageDigest.isEqual(pass, password.toByteArray())
        output.write(byteArrayOf(1, if (ok) 0 else 1))
        if (!ok) {
            counters.authFailed.incrementAndGet()
            AppLog.w("direct", "rejected client with wrong credentials")
        }
        return ok
    }

    /** @return true 表示連線已交給 [relay]。 */
    private fun connect(client: SocketChannel, output: OutputStream, request: Socks5Request): Boolean {
        val route = routeFor(request)
        if (route is ExitRoute.Socks5) {
            return connectViaUpstreamSocks5(client, output, request, route.upstream)
        }
        val startedAt = System.currentTimeMillis()
        val addresses = try {
            request.address?.let { arrayOf(it) } ?: resolve(request.host!!)
        } catch (ex: IOException) {
            counters.connectFailed.incrementAndGet()
            AppLog.d("direct", "resolve ${request.host} failed after ${System.currentTimeMillis() - startedAt}ms: ${ex.message}")
            output.write(Socks5Wire.reply(Socks5Wire.REP_HOST_UNREACHABLE))
            return false
        }
        var remote: SocketChannel? = null
        var lastError: IOException? = null
        // IPv4 優先（很多網路沒有可用的 IPv6），多個位址時前面的位址只等 3 秒，避免一個壞位址拖住整條連線。
        val ordered = addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
        for ((index, address) in ordered.withIndex()) {
            val socket = SocketChannel.open()
            runCatching { bindSocket(socket.socket()) }
            val timeout = if (index < ordered.lastIndex) minOf(PER_ADDRESS_TIMEOUT_MS, connectTimeoutMs) else connectTimeoutMs
            try {
                socket.socket().tcpNoDelay = true
                socket.socket().connect(InetSocketAddress(address, request.port), timeout)
                remote = socket
                break
            } catch (ex: IOException) {
                lastError = ex
                closeQuietly(socket)
            }
        }
        if (remote == null) {
            counters.connectFailed.incrementAndGet()
            AppLog.d("direct", "connect ${request.host ?: request.address?.hostAddress}:${request.port} failed: ${lastError?.message}")
            val code = if (lastError is java.net.ConnectException) {
                Socks5Wire.REP_CONNECTION_REFUSED
            } else {
                Socks5Wire.REP_HOST_UNREACHABLE
            }
            output.write(Socks5Wire.reply(code))
            return false
        }
        try {
            output.write(Socks5Wire.reply(Socks5Wire.REP_SUCCEEDED))
            client.socket().soTimeout = 0
        } catch (ex: IOException) {
            closeQuietly(remote)
            throw ex
        }
        relay.register(client, remote)
        return true
    }

    /** 透過外部上游 SOCKS5 代理建立 CONNECT 連線，保留原始域名讓代理端分流規則生效。 */
    private fun connectViaUpstreamSocks5(
        client: SocketChannel,
        output: OutputStream,
        request: Socks5Request,
        upstream: UpstreamSocks5Config,
    ): Boolean {
        val remote = SocketChannel.open()
        try {
            val isLoopback = upstream.host == "127.0.0.1" || upstream.host.equals("localhost", ignoreCase = true) || upstream.host == "::1"
            if (!isLoopback) {
                runCatching { bindSocket(remote.socket()) }
            }
            remote.socket().tcpNoDelay = true
            remote.socket().soTimeout = HANDSHAKE_TIMEOUT_MS
            remote.socket().connect(InetSocketAddress(upstream.host, upstream.port), connectTimeoutMs)
            val upIn = DataInputStream(remote.socket().getInputStream())
            val upOut = remote.socket().getOutputStream()

            val hasAuth = upstream.username.isNotEmpty() || upstream.password.isNotEmpty()
            if (hasAuth) {
                upOut.write(byteArrayOf(Socks5Wire.VERSION.toByte(), 2, 0x00, Socks5Wire.METHOD_USER_PASS.toByte()))
            } else {
                upOut.write(byteArrayOf(Socks5Wire.VERSION.toByte(), 1, 0x00))
            }
            upOut.flush()
            val ver = upIn.readUnsignedByte()
            val chosenMethod = upIn.readUnsignedByte()
            if (ver != Socks5Wire.VERSION || chosenMethod == 0xFF) {
                throw IOException("upstream SOCKS5 rejected auth methods ($chosenMethod)")
            }
            if (chosenMethod == Socks5Wire.METHOD_USER_PASS) {
                val uBytes = upstream.username.toByteArray(Charsets.UTF_8)
                val pBytes = upstream.password.toByteArray(Charsets.UTF_8)
                val authBuf = ByteArray(3 + uBytes.size + pBytes.size)
                authBuf[0] = 1
                authBuf[1] = uBytes.size.toByte()
                System.arraycopy(uBytes, 0, authBuf, 2, uBytes.size)
                authBuf[2 + uBytes.size] = pBytes.size.toByte()
                System.arraycopy(pBytes, 0, authBuf, 3 + uBytes.size, pBytes.size)
                upOut.write(authBuf)
                upOut.flush()
                val authVer = upIn.readUnsignedByte()
                val authStatus = upIn.readUnsignedByte()
                if (authVer != 1 || authStatus != 0) {
                    throw IOException("upstream SOCKS5 auth failed ($authStatus)")
                }
            }

            // 轉送 CONNECT 請求（優先保留域名以維持上游代理的域名分流規則）
            val reqBytes = buildConnectRequest(request)
            upOut.write(reqBytes)
            upOut.flush()

            val repVer = upIn.readUnsignedByte()
            val repCode = upIn.readUnsignedByte()
            upIn.readUnsignedByte() // RSV
            val atyp = upIn.readUnsignedByte()
            when (atyp) {
                Socks5Wire.ATYP_IPV4 -> upIn.skipBytes(4)
                Socks5Wire.ATYP_IPV6 -> upIn.skipBytes(16)
                Socks5Wire.ATYP_DOMAIN -> {
                    val len = upIn.readUnsignedByte()
                    upIn.skipBytes(len)
                }
                else -> throw IOException("upstream SOCKS5 unknown atyp $atyp")
            }
            upIn.skipBytes(2) // BND.PORT
            if (repVer != Socks5Wire.VERSION || repCode != Socks5Wire.REP_SUCCEEDED) {
                counters.connectFailed.incrementAndGet()
                output.write(Socks5Wire.reply(if (repCode in 1..8) repCode else Socks5Wire.REP_GENERAL_FAILURE))
                closeQuietly(remote)
                return false
            }

            remote.socket().soTimeout = 0
            output.write(Socks5Wire.reply(Socks5Wire.REP_SUCCEEDED))
            client.socket().soTimeout = 0
            counters.upstreamProxied.incrementAndGet()
            relay.register(client, remote)
            return true
        } catch (ex: IOException) {
            counters.connectFailed.incrementAndGet()
            AppLog.d("direct", "upstream SOCKS5 ${upstream.host}:${upstream.port} failed: ${ex.message}")
            runCatching { output.write(Socks5Wire.reply(Socks5Wire.REP_HOST_UNREACHABLE)) }
            closeQuietly(remote)
            return false
        }
    }

    private fun buildConnectRequest(request: Socks5Request): ByteArray {
        val portHi = ((request.port ushr 8) and 0xFF).toByte()
        val portLo = (request.port and 0xFF).toByte()
        val host = request.host
        if (host != null) {
            val h = host.toByteArray(Charsets.US_ASCII)
            val buf = ByteArray(4 + 1 + h.size + 2)
            buf[0] = Socks5Wire.VERSION.toByte()
            buf[1] = Socks5Wire.CMD_CONNECT.toByte()
            buf[2] = 0
            buf[3] = Socks5Wire.ATYP_DOMAIN.toByte()
            buf[4] = h.size.toByte()
            System.arraycopy(h, 0, buf, 5, h.size)
            buf[5 + h.size] = portHi
            buf[6 + h.size] = portLo
            return buf
        }
        val addrBytes = request.address!!.address
        val atyp = if (addrBytes.size == 4) Socks5Wire.ATYP_IPV4 else Socks5Wire.ATYP_IPV6
        val buf = ByteArray(4 + addrBytes.size + 2)
        buf[0] = Socks5Wire.VERSION.toByte()
        buf[1] = Socks5Wire.CMD_CONNECT.toByte()
        buf[2] = 0
        buf[3] = atyp.toByte()
        System.arraycopy(addrBytes, 0, buf, 4, addrBytes.size)
        buf[4 + addrBytes.size] = portHi
        buf[5 + addrBytes.size] = portLo
        return buf
    }

    private fun associate(client: Socket, input: InputStream, output: OutputStream) {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val local = DatagramSocket(InetSocketAddress(loopback, 0))
        val outbound = DatagramSocket()
        runCatching { bindDatagram(outbound) }
        track(local)
        track(outbound)
        counters.udpAssociations.incrementAndGet()
        val association = UdpAssociation(local, outbound)
        try {
            output.write(Socks5Wire.reply(Socks5Wire.REP_SUCCEEDED, InetSocketAddress(loopback, local.localPort)))
            if (!submit { association.pumpFromRemote() }) return
            if (!submit { association.pumpFromClient() }) return
            // 關聯在控制連線關閉時結束（RFC 1928 §7）。
            client.soTimeout = 0
            val buffer = ByteArray(64)
            while (input.read(buffer) >= 0) Unit
        } finally {
            association.close()
        }
    }

    private inner class UdpAssociation(
        private val local: DatagramSocket,
        private val outbound: DatagramSocket,
    ) {
        @Volatile
        private var clientAddress: InetSocketAddress? = null
        @Volatile
        private var upstreamRelay: UpstreamUdpRelay? = null
        private val upstreamLock = Any()
        private val resolved = object : LinkedHashMap<String, InetAddress>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InetAddress>?) = size > 256
        }

        fun pumpFromClient() {
            val buffer = ByteArray(UDP_BUFFER)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (!local.isClosed) {
                    packet.setData(buffer, 0, buffer.size)
                    local.receive(packet)
                    // 只接受本機 hev 送來的封包，並記住它的位址供回送。
                    if (!packet.address.isLoopbackAddress) continue
                    clientAddress = InetSocketAddress(packet.address, packet.port)
                    val datagram = Socks5Wire.parseUdp(buffer, packet.length) ?: continue
                    val route = routeFor(Socks5Request(Socks5Wire.CMD_UDP_ASSOCIATE, datagram.address, datagram.host, datagram.port))
                    if (route is ExitRoute.Socks5) {
                        val relay = ensureUpstreamRelay(route.upstream) ?: continue
                        relay.sendRaw(buffer, packet.length)
                        counters.upstreamUdpProxied.incrementAndGet()
                        continue
                    }
                    val target = datagram.address ?: resolveCached(datagram.host!!) ?: continue
                    outbound.send(
                        DatagramPacket(buffer, datagram.payloadOffset, packet.length - datagram.payloadOffset, target, datagram.port),
                    )
                }
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        fun pumpFromRemote() {
            val buffer = ByteArray(UDP_BUFFER)
            val packet = DatagramPacket(buffer, Socks5Wire.UDP_HEADER_MAX, buffer.size - Socks5Wire.UDP_HEADER_MAX)
            try {
                while (!outbound.isClosed) {
                    packet.setData(buffer, Socks5Wire.UDP_HEADER_MAX, buffer.size - Socks5Wire.UDP_HEADER_MAX)
                    outbound.receive(packet)
                    val client = clientAddress ?: continue
                    val header = Socks5Wire.udpHeader(packet.address, packet.port)
                    val start = Socks5Wire.UDP_HEADER_MAX - header.size
                    header.copyInto(buffer, start)
                    local.send(DatagramPacket(buffer, start, header.size + packet.length, client))
                }
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        private fun ensureUpstreamRelay(upstream: UpstreamSocks5Config): UpstreamUdpRelay? {
            upstreamRelay?.takeIf { !it.isClosed }?.let { return it }
            synchronized(upstreamLock) {
                upstreamRelay?.takeIf { !it.isClosed }?.let { return it }
                val created = runCatching { openUpstreamUdpRelay(upstream) }.getOrElse { err ->
                    AppLog.d("direct", "upstream SOCKS5 UDP associate failed: ${err.message}")
                    null
                } ?: return null
                upstreamRelay = created
                submit { created.pumpToClient() }
                return created
            }
        }

        private fun openUpstreamUdpRelay(upstream: UpstreamSocks5Config): UpstreamUdpRelay {
            val control = Socket()
            val isLoopback = upstream.host == "127.0.0.1" || upstream.host.equals("localhost", ignoreCase = true) || upstream.host == "::1"
            if (!isLoopback) {
                runCatching { bindSocket(control) }
            }
            try {
                control.tcpNoDelay = true
                control.soTimeout = HANDSHAKE_TIMEOUT_MS
                control.connect(InetSocketAddress(upstream.host, upstream.port), connectTimeoutMs)
                val upIn = DataInputStream(control.getInputStream())
                val upOut = control.getOutputStream()

                val hasAuth = upstream.username.isNotEmpty() || upstream.password.isNotEmpty()
                if (hasAuth) {
                    upOut.write(byteArrayOf(Socks5Wire.VERSION.toByte(), 2, 0x00, Socks5Wire.METHOD_USER_PASS.toByte()))
                } else {
                    upOut.write(byteArrayOf(Socks5Wire.VERSION.toByte(), 1, 0x00))
                }
                upOut.flush()
                val ver = upIn.readUnsignedByte()
                val chosenMethod = upIn.readUnsignedByte()
                if (ver != Socks5Wire.VERSION || chosenMethod == 0xFF) {
                    throw IOException("upstream UDP auth rejected ($chosenMethod)")
                }
                if (chosenMethod == Socks5Wire.METHOD_USER_PASS) {
                    val uBytes = upstream.username.toByteArray(Charsets.UTF_8)
                    val pBytes = upstream.password.toByteArray(Charsets.UTF_8)
                    val authBuf = ByteArray(3 + uBytes.size + pBytes.size)
                    authBuf[0] = 1
                    authBuf[1] = uBytes.size.toByte()
                    System.arraycopy(uBytes, 0, authBuf, 2, uBytes.size)
                    authBuf[2 + uBytes.size] = pBytes.size.toByte()
                    System.arraycopy(pBytes, 0, authBuf, 3 + uBytes.size, pBytes.size)
                    upOut.write(authBuf)
                    upOut.flush()
                    if (upIn.readUnsignedByte() != 1 || upIn.readUnsignedByte() != 0) {
                        throw IOException("upstream UDP user/pass auth failed")
                    }
                }
                // 發送 UDP ASSOCIATE 0.0.0.0:0
                upOut.write(byteArrayOf(Socks5Wire.VERSION.toByte(), Socks5Wire.CMD_UDP_ASSOCIATE.toByte(), 0, Socks5Wire.ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
                upOut.flush()
                val repVer = upIn.readUnsignedByte()
                val repCode = upIn.readUnsignedByte()
                upIn.readUnsignedByte()
                val atyp = upIn.readUnsignedByte()
                val rawAddr: InetAddress = when (atyp) {
                    Socks5Wire.ATYP_IPV4 -> InetAddress.getByAddress(ByteArray(4).also { upIn.readFully(it) })
                    Socks5Wire.ATYP_IPV6 -> InetAddress.getByAddress(ByteArray(16).also { upIn.readFully(it) })
                    Socks5Wire.ATYP_DOMAIN -> {
                        val host = String(ByteArray(upIn.readUnsignedByte()).also { upIn.readFully(it) }, Charsets.US_ASCII)
                        InetAddress.getByName(host)
                    }
                    else -> throw IOException("upstream UDP unknown atyp $atyp")
                }
                val bndPort = upIn.readUnsignedShort()
                if (repVer != Socks5Wire.VERSION || repCode != Socks5Wire.REP_SUCCEEDED || bndPort <= 0) {
                    throw IOException("upstream UDP associate failed (rep=$repCode port=$bndPort)")
                }
                val relayHost = if (rawAddr.isAnyLocalAddress) InetAddress.getByName(upstream.host) else rawAddr
                val udpSocket = DatagramSocket()
                if (!isLoopback) {
                    runCatching { bindDatagram(udpSocket) }
                }
                track(control)
                track(udpSocket)
                return UpstreamUdpRelay(control, udpSocket, InetSocketAddress(relayHost, bndPort))
            } catch (ex: IOException) {
                closeQuietly(control)
                throw ex
            }
        }

        private inner class UpstreamUdpRelay(
            private val control: Socket,
            private val udpSocket: DatagramSocket,
            private val relayAddress: InetSocketAddress,
        ) {
            @Volatile
            var isClosed = false
                private set

            fun sendRaw(buffer: ByteArray, length: Int) {
                udpSocket.send(DatagramPacket(buffer, 0, length, relayAddress))
            }

            fun pumpToClient() {
                val buf = ByteArray(UDP_BUFFER)
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    while (!isClosed && !udpSocket.isClosed && !local.isClosed) {
                        pkt.setData(buf, 0, buf.size)
                        udpSocket.receive(pkt)
                        val client = clientAddress ?: continue
                        // 上游 SOCKS5 回傳的資料已帶 RFC 1928 UDP 標頭，直接轉回給本機 hev。
                        local.send(DatagramPacket(buf, 0, pkt.length, client))
                    }
                } catch (_: IOException) {
                } finally {
                    close()
                }
            }

            fun close() {
                isClosed = true
                closeQuietly(udpSocket)
                closeQuietly(control)
            }
        }

        private fun resolveCached(host: String): InetAddress? {
            synchronized(resolved) { resolved[host] }?.let { return it }
            val address = runCatching { resolve(host).firstOrNull() }.getOrNull() ?: return null
            synchronized(resolved) { resolved[host] = address }
            return address
        }

        fun close() {
            upstreamRelay?.close()
            upstreamRelay = null
            closeQuietly(local)
            closeQuietly(outbound)
        }
    }

    private fun track(closeable: java.io.Closeable) {
        sockets += closeable
        if (closed) closeQuietly(closeable)
    }

    private fun closeQuietly(closeable: java.io.Closeable) {
        sockets -= closeable
        runCatching { closeable.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        relay.close()
        // ConcurrentHashMap 的迭代器在並發移除時是安全的；不能先 toList()（大小與內容可能不一致而拋例外）。
        for (closeable in sockets) runCatching { closeable.close() }
        sockets.clear()
        pool.shutdownNow()
        AppLog.i("direct", "direct exit stopped")
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val PER_ADDRESS_TIMEOUT_MS = 3_000
        private const val UDP_BUFFER = 65_535

        private fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** SOCKS5 請求：[address] 與 [host] 二擇一（域名時 [address] 為 null）。 */
data class Socks5Request(val command: Int, val address: InetAddress?, val host: String?, val port: Int)

/** SOCKS5 UDP 資料報：資料從 [payloadOffset] 開始。 */
data class Socks5Datagram(val address: InetAddress?, val host: String?, val port: Int, val payloadOffset: Int)

/** SOCKS5（RFC 1928 / 1929）線路格式；純函數，方便單元測試。 */
object Socks5Wire {
    const val VERSION = 5
    const val METHOD_USER_PASS = 2
    const val CMD_CONNECT = 1
    const val CMD_UDP_ASSOCIATE = 3
    const val ATYP_IPV4 = 1
    const val ATYP_DOMAIN = 3
    const val ATYP_IPV6 = 4
    const val REP_SUCCEEDED = 0
    const val REP_GENERAL_FAILURE = 1
    const val REP_HOST_UNREACHABLE = 4
    const val REP_CONNECTION_REFUSED = 5
    const val REP_COMMAND_NOT_SUPPORTED = 7

    /** UDP 標頭最大長度：RSV(2) + FRAG(1) + ATYP(1) + IPv6(16) + PORT(2)。回送時只會用 IP 位址。 */
    const val UDP_HEADER_MAX = 22

    fun readRequest(input: DataInputStream): Socks5Request? {
        if (input.readUnsignedByte() != VERSION) return null
        val command = input.readUnsignedByte()
        input.readUnsignedByte()
        return when (input.readUnsignedByte()) {
            ATYP_IPV4 -> Socks5Request(command, InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }), null, input.readUnsignedShort())
            ATYP_IPV6 -> Socks5Request(command, InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }), null, input.readUnsignedShort())
            ATYP_DOMAIN -> {
                val name = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                Socks5Request(command, null, String(name, Charsets.US_ASCII), input.readUnsignedShort())
            }
            else -> null
        }
    }

    fun reply(code: Int, bound: InetSocketAddress? = null): ByteArray {
        val address = bound?.address?.address ?: byteArrayOf(0, 0, 0, 0)
        val atyp = if (address.size == 16) ATYP_IPV6 else ATYP_IPV4
        val port = bound?.port ?: 0
        return byteArrayOf(VERSION.toByte(), code.toByte(), 0, atyp.toByte()) +
            address + byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    fun parseUdp(buffer: ByteArray, length: Int): Socks5Datagram? {
        if (length < 10) return null
        // 不支援分片（FRAG != 0），與多數實作一致。
        if (buffer[2].toInt() != 0) return null
        return when (buffer[3].toInt() and 0xFF) {
            ATYP_IPV4 -> if (length < 10) null else Socks5Datagram(
                InetAddress.getByAddress(buffer.copyOfRange(4, 8)), null, u16(buffer, 8), 10,
            )
            ATYP_IPV6 -> if (length < 22) null else Socks5Datagram(
                InetAddress.getByAddress(buffer.copyOfRange(4, 20)), null, u16(buffer, 20), 22,
            )
            ATYP_DOMAIN -> {
                val nameLength = buffer[4].toInt() and 0xFF
                val end = 5 + nameLength
                if (nameLength == 0 || end + 2 > length) {
                    null
                } else {
                    Socks5Datagram(null, String(buffer, 5, nameLength, Charsets.US_ASCII), u16(buffer, end), end + 2)
                }
            }
            else -> null
        }
    }

    fun udpHeader(address: InetAddress, port: Int): ByteArray {
        val raw = address.address
        val atyp = when (address) {
            is Inet4Address -> ATYP_IPV4
            is Inet6Address -> ATYP_IPV6
            else -> ATYP_IPV4
        }
        return byteArrayOf(0, 0, 0, atyp.toByte()) + raw + byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
