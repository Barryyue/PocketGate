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
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
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
}

/**
 * 內建的「直連出口」：只監聽 127.0.0.1 的 SOCKS5 伺服器，hev 把已經過防火牆判決的連線交給它，
 * 由它以本應用的身分直接連到目標。本應用被排除在 VPN 之外，所以這些連線不會再回到虛擬網卡。
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
) : AutoCloseable {
    val username: String = randomToken()
    val password: String = randomToken()
    val counters = DirectSocksCounters()

    private val server = ServerSocket(0, 256, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int get() = server.localPort

    private val threadIds = AtomicInteger()
    private val pool = ThreadPoolExecutor(
        0,
        maxThreads,
        30,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { runnable ->
            // 每條連線兩個執行緒，堆疊給小一點以控制記憶體。
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
            val socket = try {
                server.accept()
            } catch (ex: IOException) {
                if (!closed) AppLog.w("direct", "accept failed: ${ex.message}")
                break
            }
            counters.accepted.incrementAndGet()
            if (!submit { handle(socket) }) {
                counters.rejected.incrementAndGet()
                runCatching { socket.close() }
            }
        }
    }

    private fun submit(task: () -> Unit): Boolean = try {
        pool.execute(task)
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        false
    }

    private fun handle(client: Socket) {
        track(client)
        counters.active.incrementAndGet()
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
                Socks5Wire.CMD_CONNECT -> connect(client, input, output, request)
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
            counters.active.decrementAndGet()
            closeQuietly(client)
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

    private fun connect(client: Socket, input: InputStream, output: OutputStream, request: Socks5Request) {
        val startedAt = System.currentTimeMillis()
        val addresses = try {
            request.address?.let { arrayOf(it) } ?: resolve(request.host!!)
        } catch (ex: IOException) {
            counters.connectFailed.incrementAndGet()
            AppLog.d("direct", "resolve ${request.host} failed after ${System.currentTimeMillis() - startedAt}ms: ${ex.message}")
            output.write(Socks5Wire.reply(Socks5Wire.REP_HOST_UNREACHABLE))
            return
        }
        var remote: Socket? = null
        var lastError: IOException? = null
        // IPv4 優先（很多網路沒有可用的 IPv6），多個位址時前面的位址只等 3 秒，避免一個壞位址拖住整條連線。
        val ordered = addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
        for ((index, address) in ordered.withIndex()) {
            val socket = Socket()
            runCatching { bindSocket(socket) }
            val timeout = if (index < ordered.lastIndex) minOf(PER_ADDRESS_TIMEOUT_MS, connectTimeoutMs) else connectTimeoutMs
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, request.port), timeout)
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
            return
        }
        track(remote)
        try {
            output.write(Socks5Wire.reply(Socks5Wire.REP_SUCCEEDED))
            client.soTimeout = 0
            val downstream = remote.getInputStream()
            val upstream = remote.getOutputStream()
            // 下行（目標 → 用戶端）在另一個執行緒；拿不到執行緒時放棄這條連線。
            val downDone = java.util.concurrent.CountDownLatch(1)
            val started = submit {
                try {
                    pump(downstream, output)
                    runCatching { client.shutdownOutput() }
                } finally {
                    downDone.countDown()
                }
            }
            if (!started) {
                counters.rejected.incrementAndGet()
                return
            }
            pump(input, upstream)
            // 用戶端送完（半關閉）後，目標可能還在回覆：轉達半關閉並等下行結束。
            runCatching { remote.shutdownOutput() }
            downDone.await(HALF_CLOSE_LINGER_MS.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            closeQuietly(remote)
        }
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

        private fun resolveCached(host: String): InetAddress? {
            synchronized(resolved) { resolved[host] }?.let { return it }
            val address = runCatching { resolve(host).firstOrNull() }.getOrNull() ?: return null
            synchronized(resolved) { resolved[host] = address }
            return address
        }

        fun close() {
            closeQuietly(local)
            closeQuietly(outbound)
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(TCP_BUFFER)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                if (read > 0) to.write(buffer, 0, read)
            }
            to.flush()
        } catch (_: SocketException) {
        } catch (_: IOException) {
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
        sockets.toList().forEach(::closeQuietly)
        pool.shutdownNow()
        AppLog.i("direct", "direct exit stopped")
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val PER_ADDRESS_TIMEOUT_MS = 3_000
        private const val HALF_CLOSE_LINGER_MS = 120_000
        private const val TCP_BUFFER = 32 * 1024
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
