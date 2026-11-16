package com.networkcontrol.android.kotlin.engine.vpn

import com.networkcontrol.android.kotlin.platform.AppLog
import java.io.File
import java.net.InetAddress

/** 轉發模式的固定網路參數。 */
object ForwardingDefaults {
    /** hev 映射 DNS 的位址：系統 DNS 指向這裡，由 hev 在本地用映射位址回答。 */
    val MapDnsAddress: InetAddress = InetAddress.getByName("10.111.222.3")
    const val MAP_DNS_PORT = 53

    /** 映射位址段（CGNAT 100.64.0.0/10），hev 會把連往這段的請求還原成域名送給 SOCKS5。 */
    const val MAP_NETWORK = "100.64.0.0"
    const val MAP_NETMASK = "255.192.0.0"
    const val MAP_CACHE_SIZE = 10_000

    /** hev 日誌超過這個大小就在下次啟動時清空。 */
    const val MAX_LOG_BYTES = 1L shl 20
}

/**
 * 把 VpnService 的流量交給 hev-socks5-tunnel（MIT）轉發到上游 SOCKS5。
 * hev 讀寫的是 [ForwardingRelay] 提供的 socketpair 一端，而不是虛擬網卡本身，
 * 這樣每條新連線都會先經過 [ForwardingPacketFilter] 判決。
 */
object VpnForwardingTransport {
    @Volatile
    private var loaded = false

    fun isAvailable(): Boolean = runCatching {
        load()
        true
    }.getOrDefault(false)

    fun start(configFile: File, fd: Int): Boolean = runCatching {
        load()
        val started = hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
        if (started) {
            AppLog.i("forwarding", "start fd=$fd running=${isRunning()}")
        } else {
            AppLog.w("forwarding", "start fd=$fd returned false")
        }
        started
    }.getOrElse {
        AppLog.e("forwarding", "start failed", it)
        false
    }

    fun stop() {
        runCatching { hev.htproxy.TProxyService.TProxyStopService() }
            .onFailure { AppLog.w("forwarding", "stop failed: ${it.message}") }
    }

    fun isRunning(): Boolean = runCatching {
        load()
        hev.htproxy.TProxyService.TProxyIsRunning()
    }.getOrDefault(false)

    fun stats(): LongArray? = runCatching { hev.htproxy.TProxyService.TProxyGetStats() }.getOrNull()

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("hev-socks5-tunnel")
            loaded = true
        }
    }
}

/**
 * 產生 hev 設定。
 * - 一律使用標準 SOCKS5 UDP ASSOCIATE（`udp: 'udp'`）；hev 的 `tcp` 模式是它自訂的 UDP-in-TCP，
 *   FlClash / mihomo / Reqable 等一般代理都不支援。「關閉 UDP」由 [ForwardingPacketFilter] 丟棄 UDP 實現。
 * - 啟用 mapdns：DNS 在本地回答，連線以域名送給代理，代理才能按域名分流，也不依賴代理支援 UDP。
 */
fun writeForwardingConfig(
    file: File,
    config: VpnTunnelConfig,
    ipv4: String = FakeDnsPool.Ipv4Address.hostAddress ?: "10.111.222.1",
    ipv6: String = FakeDnsPool.Ipv6Address.hostAddress ?: "fd00:6e63::1",
    logLevel: String = "warn",
) {
    val host = config.upstreamHost.ifBlank { "127.0.0.1" }
    val port = config.upstreamPort.coerceIn(1, 65535)
    val logFile = File(file.parentFile ?: File("."), "hev.log")
    if (logFile.length() > ForwardingDefaults.MAX_LOG_BYTES) {
        runCatching { logFile.writeText("") }
    }
    file.writeText(
        buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${config.mtu}")
            appendLine("  ipv4: $ipv4")
            appendLine("  ipv6: '$ipv6'")
            appendLine("  offload:")
            appendLine("    tso: false")
            appendLine("    uso: false")
            appendLine("    jumbo: false")
            appendLine("socks5:")
            appendLine("  address: $host")
            appendLine("  port: $port")
            appendLine("  udp: 'udp'")
            val user = config.upstreamUsername
            val pass = config.upstreamPassword
            if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                appendLine("  username: '${user.replace("'", "''")}'")
                appendLine("  password: '${pass.replace("'", "''")}'")
            }
            appendLine("mapdns:")
            appendLine("  address: ${ForwardingDefaults.MapDnsAddress.hostAddress}")
            appendLine("  port: ${ForwardingDefaults.MAP_DNS_PORT}")
            appendLine("  network: ${ForwardingDefaults.MAP_NETWORK}")
            appendLine("  netmask: ${ForwardingDefaults.MAP_NETMASK}")
            appendLine("  cache-size: ${ForwardingDefaults.MAP_CACHE_SIZE}")
            appendLine("misc:")
            appendLine("  task-stack-size: 86016")
            appendLine("  log-level: $logLevel")
            appendLine("  log-file: '${logFile.absolutePath}'")
        },
    )
}
