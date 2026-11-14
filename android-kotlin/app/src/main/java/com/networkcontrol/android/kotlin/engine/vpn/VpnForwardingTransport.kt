package com.networkcontrol.android.kotlin.engine.vpn

import com.networkcontrol.android.kotlin.platform.AppLog
import java.io.File

/**
 * Phase 0 轉發 spike：把 VpnService 的 TUN fd 交給 hev-socks5-tunnel。
 * 目前只處理「所有流量轉到本機 SOCKS5」，尚未接入 per-flow 判決。
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
            Thread.sleep(200)
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

fun writeForwardingConfig(
    file: File,
    config: VpnTunnelConfig,
    ipv4: String = "198.18.0.1",
    ipv6: String = "fc00::1",
) {
    val host = config.upstreamHost.ifBlank { "127.0.0.1" }
    val port = config.upstreamPort.coerceIn(1, 65535)
    val udp = if (config.upstreamUdp) "udp" else "tcp"
    file.writeText(
        buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${config.mtu}")
            appendLine("  ipv4: $ipv4")
            appendLine("  ipv6: '$ipv6'")
            appendLine("socks5:")
            appendLine("  address: $host")
            appendLine("  port: $port")
            appendLine("  udp: '$udp'")
            appendLine("misc:")
            appendLine("  task-stack-size: 24576")
            appendLine("  log-level: debug")
            appendLine("  log-file: '${file.parentFile?.absolutePath ?: "."}/hev.log'")
        },
    )
}
