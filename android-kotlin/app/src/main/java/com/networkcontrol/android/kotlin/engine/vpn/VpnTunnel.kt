package com.networkcontrol.android.kotlin.engine.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.networkcontrol.android.kotlin.App
import com.networkcontrol.android.kotlin.AppPreferences
import com.networkcontrol.android.kotlin.MainActivity
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.platform.NotificationChannels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

enum class VpnTunnelState {
    NotPrepared,
    Establishing,
    Active,
    Closed,
    Revoked,
    Failed,
}

data class VpnTunnelConfig(
    val bypassPackages: Set<String>,
    val mtu: Int = 1500,
    val forwarding: Boolean = false,
    val upstreamHost: String = "127.0.0.1",
    val upstreamPort: Int = 7890,
    val upstreamUdp: Boolean = true,
    /** 上游 SOCKS5 的帳號密碼；內建直連出口一律使用，外部代理不使用。 */
    val upstreamUsername: String? = null,
    val upstreamPassword: String? = null,
) {
    fun equivalentTo(other: VpnTunnelConfig?): Boolean =
        other != null &&
            mtu == other.mtu &&
            forwarding == other.forwarding &&
            upstreamHost == other.upstreamHost &&
            upstreamPort == other.upstreamPort &&
            upstreamUsername == other.upstreamUsername &&
            upstreamPassword == other.upstreamPassword &&
            bypassPackages == other.bypassPackages
}

interface PacketWriter {
    fun write(packet: ByteArray)
}

interface PacketSink {
    fun onPacket(packet: ByteArray, length: Int, writer: PacketWriter)
}

class TunReader(
    private val pfd: ParcelFileDescriptor,
    private val sink: PacketSink,
    private val onError: (Throwable) -> Unit,
) : PacketWriter, AutoCloseable {
    private val input = FileInputStream(pfd.fileDescriptor)
    private val output = FileOutputStream(pfd.fileDescriptor)
    @Volatile
    private var stopped = false
    private val thread = Thread({ run() }, "NetworkControlKT.TunReader").apply {
        isDaemon = true
        start()
    }

    private fun run() {
        val buffer = ByteArray(32768)
        try {
            while (!stopped) {
                val read = input.read(buffer)
                if (read > 0) {
                    sink.onPacket(buffer, read, this)
                } else if (read < 0) {
                    break
                }
            }
        } catch (ex: IOException) {
            if (!stopped) onError(ex)
        } catch (ex: Throwable) {
            if (!stopped) onError(ex)
        }
    }

    override fun write(packet: ByteArray) {
        if (stopped) return
        runCatching { synchronized(output) { output.write(packet); output.flush() } }
    }

    override fun close() {
        stopped = true
        runCatching { pfd.close() }
        runCatching { input.close() }
        runCatching { output.close() }
        if (Thread.currentThread() !== thread) {
            runCatching { thread.join(2_000) }
        }
    }
}

/** 空白或未設定時使用本地化預設標題。 */
internal fun notificationTitle(custom: String?, default: String): String =
    custom?.trim()?.takeIf { it.isNotEmpty() } ?: default

class VpnForegroundNotifier(
    private val context: Context,
    private val preferences: AppPreferences,
) {
    val notificationId: Int = 1001

    @Volatile
    private var speed: com.networkcontrol.android.kotlin.engine.traffic.SpeedSnapshot? = null

    fun setSpeed(value: com.networkcontrol.android.kotlin.engine.traffic.SpeedSnapshot?) {
        speed = value
    }

    fun build(starting: Boolean = false): Notification {
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = when {
            starting -> context.getString(R.string.protection_notification_text)
            speed != null -> {
                val current = speed!!
                "↓ ${formatRate(current.downloadBytesPerSecond)}/s · ↑ ${formatRate(current.uploadBytesPerSecond)}/s"
            }
            else -> context.getString(R.string.protection_notification_text)
        }
        val title = if (starting) {
            context.getString(R.string.protection_starting)
        } else {
            notificationTitle(
                preferences.protectionNotificationTitle,
                context.getString(R.string.protection_active),
            )
        }
        return NotificationCompat.Builder(context, NotificationChannels.PROTECTION)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(intent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun formatRate(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return "%.1f %s".format(value, units[index])
    }
}

class VpnTunnelController(
    private val context: Context,
    private val notifier: VpnForegroundNotifier,
) {
    private val appContext = context.applicationContext
    private val gate = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var service: NcVpnService? = null
    private var ready = CompletableDeferred<NcVpnService>()
    private var reader: TunReader? = null
    private var relay: ForwardingRelay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var applied: VpnTunnelConfig? = null
    private var generation = 0L
    @Volatile
    private var forwardingActive = false

    @Volatile
    var state: VpnTunnelState = VpnTunnelState.Closed
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 目前隧道是否真的在轉發（轉發啟動失敗並回退到黑洞模式時為 false）。 */
    val isForwarding: Boolean get() = forwardingActive

    var onStateChanged: (() -> Unit)? = null
    var onRevoked: (() -> Unit)? = null

    /** 服務被結束（不是被撤銷）時呼叫；防護仍開啟時由引擎決定是否重建。 */
    var onDetached: (() -> Unit)? = null

    val isPrepared: Boolean get() = VpnService.prepare(appContext) == null

    fun createPrepareIntent(): Intent? = VpnService.prepare(appContext)

    /**
     * 建立或重建隧道。
     * @param forwardingFilter 轉發模式的逐連線過濾層；[config] 要求轉發時必須提供。
     * @param fallback 轉發無法啟動時改用的黑洞模式設定（按路由計畫排除應用、使用假 DNS）。
     */
    suspend fun apply(
        config: VpnTunnelConfig,
        sink: PacketSink,
        forwardingFilter: ForwardingPacketFilter? = null,
        fallback: VpnTunnelConfig? = null,
    ) {
        gate.withLock {
            if (!isPrepared) {
                setState(VpnTunnelState.NotPrepared)
                lastError = "尚未取得建立 VPN 的授權。"
                return
            }
            setState(VpnTunnelState.Establishing)
            val svc = try {
                ensureService()
            } catch (ex: Exception) {
                lastError = "啟動 VPN 服務失敗：${ex.message}"
                setState(VpnTunnelState.Failed)
                return
            }

            val myGeneration = ++generation
            var effective = config
            var descriptor = establishOrFail(svc, effective) ?: return
            releaseLocked()
            var warning: String? = null

            if (effective.forwarding) {
                val started = forwardingFilter != null && startForwarding(descriptor, effective, forwardingFilter, myGeneration)
                if (!started) {
                    warning = "轉發啟動失敗（可能是 ABI 不支援或上游設定錯誤），已改用黑洞模式。"
                    com.networkcontrol.android.kotlin.platform.AppLog.w("forwarding", warning)
                    runCatching { descriptor.close() }
                    effective = fallback ?: config.copy(forwarding = false)
                    descriptor = establishOrFail(svc, effective) ?: return
                }
            }
            pfd = descriptor
            if (!forwardingActive) {
                reader = TunReader(descriptor, sink) { error ->
                    if (myGeneration == generation) {
                        lastError = "讀取虛擬網卡失敗：${error.message}"
                        setState(VpnTunnelState.Failed)
                    }
                }
            }
            runCatching { svc.ensureForeground(notifier.notificationId, notifier.build()) }
                .onFailure { warning = warning ?: "無法建立前景通知：${it.message}" }
            applied = config
            lastError = warning
            setState(VpnTunnelState.Active)
            com.networkcontrol.android.kotlin.platform.AppLog.i(
                "vpn",
                "tunnel active forwarding=$forwardingActive bypass=${effective.bypassPackages.size}",
            )
        }
    }

    private fun establishOrFail(svc: NcVpnService, config: VpnTunnelConfig): ParcelFileDescriptor? {
        val descriptor = try {
            svc.establish(config)
        } catch (ex: Exception) {
            lastError = "建立虛擬網卡失敗：${ex.message}"
            setState(VpnTunnelState.Failed)
            return null
        }
        if (descriptor == null) {
            lastError = "系統拒絕建立 VPN（可能已被其他 VPN 取代）。"
            setState(VpnTunnelState.Failed)
        }
        return descriptor
    }

    private fun startForwarding(
        descriptor: ParcelFileDescriptor,
        config: VpnTunnelConfig,
        filter: ForwardingPacketFilter,
        myGeneration: Long,
    ): Boolean {
        if (!VpnForwardingTransport.isAvailable()) return false
        val newRelay = runCatching {
            ForwardingRelay(descriptor, filter) { error ->
                if (myGeneration == generation) {
                    lastError = "轉發通道中斷：${error.message}"
                    setState(VpnTunnelState.Failed)
                }
            }
        }.getOrElse {
            com.networkcontrol.android.kotlin.platform.AppLog.e("forwarding", "relay create failed", it)
            return false
        }
        val file = java.io.File(appContext.filesDir, "hev-forwarding.yml")
        val started = runCatching {
            // 診斷用：files 目錄下有 hev-debug 檔案時，hev 以 debug 級別記錄（日誌超過 1 MB 仍會清空）。
            val debug = java.io.File(appContext.filesDir, "hev-debug").exists()
            writeForwardingConfig(file, config, logLevel = if (debug) "debug" else "warn")
            VpnForwardingTransport.start(file, newRelay.hevSide.fd)
        }.getOrDefault(false)
        if (!started) {
            newRelay.close()
            return false
        }
        // 先重置過濾層與 DnsPacer，再啟動轉送與映射重放，避免清除剛建立的緩存或殘留舊隧道的在途 DNS。
        filter.reset()
        newRelay.start()
        relay = newRelay
        forwardingActive = true
        return true
    }

    /** 釋放目前的轉發 / 讀取資源；必須在 [gate] 內呼叫。順序：先停 hev，再關轉送與虛擬網卡。 */
    private fun releaseLocked() {
        if (forwardingActive) {
            VpnForwardingTransport.stop()
            forwardingActive = false
        }
        relay?.close()
        relay = null
        reader?.close()
        reader = null
        pfd?.close()
        pfd = null
    }

    suspend fun close() {
        gate.withLock {
            generation++
            releaseLocked()
            service?.shutdown()
            applied = null
            if (state != VpnTunnelState.Revoked && state != VpnTunnelState.NotPrepared) {
                setState(VpnTunnelState.Closed)
            }
        }
    }

    fun refreshNotification() {
        service?.updateForeground(notifier.notificationId, notifier.build())
    }

    fun attach(service: NcVpnService) {
        this.service = service
        if (!ready.isCompleted) ready.complete(service)
    }

    fun detach(service: NcVpnService) {
        if (this.service !== service) return
        this.service = null
        ready = CompletableDeferred()
        generation++
        cleanupScope.launch {
            gate.withLock {
                if (this@VpnTunnelController.service == null) {
                    releaseLocked()
                    applied = null
                }
            }
        }
        if (state == VpnTunnelState.Active || state == VpnTunnelState.Establishing) {
            setState(VpnTunnelState.Closed)
        }
        onDetached?.invoke()
    }

    fun revoke(service: NcVpnService) {
        if (this.service !== service) return
        this.service = null
        ready = CompletableDeferred()
        generation++
        lastError = "VPN 已被系統撤銷（可能啟用了其他 VPN）。"
        setState(VpnTunnelState.Revoked)
        com.networkcontrol.android.kotlin.platform.AppLog.w("vpn", "tunnel revoked")
        cleanupScope.launch {
            gate.withLock {
                if (this@VpnTunnelController.service == null) {
                    releaseLocked()
                    applied = null
                }
            }
        }
        onRevoked?.invoke()
    }

    /** 設定一則不影響隧道狀態的提示（例如上游代理暫時無法連線）；傳 null 清除。 */
    fun setWarning(message: String?) {
        if (state != VpnTunnelState.Active) return
        if (lastError == message) return
        lastError = message
        onStateChanged?.invoke()
    }

    private suspend fun ensureService(): NcVpnService {
        service?.let { return it }
        val intent = Intent(appContext, NcVpnService::class.java).setAction(NcVpnService.ACTION_START)
        ContextCompat.startForegroundService(appContext, intent)
        return withTimeout(10_000) { ready.await() }
    }

    private fun setState(value: VpnTunnelState) {
        state = value
        onStateChanged?.invoke()
    }
}

class NcVpnService : VpnService() {
    private var foreground = false
    private val controller: VpnTunnelController
        get() = App.container(this).vpnTunnel
    private val notifier: VpnForegroundNotifier
        get() = App.container(this).vpnNotifier

    override fun onCreate() {
        super.onCreate()
        controller.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground(notifier.notificationId, notifier.build(starting = true))
        if (intent?.action != ACTION_START) {
            App.container(this).vpnEngine.onSystemStart()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        controller.revoke(this)
        super.onRevoke()
        stopSelf()
    }

    override fun onDestroy() {
        controller.detach(this)
        super.onDestroy()
    }

    internal fun establish(config: VpnTunnelConfig): ParcelFileDescriptor? {
        val builder = Builder()
        builder.setSession(SESSION_NAME)
        builder.setMtu(config.mtu)
        builder.addAddress(FakeDnsPool.Ipv4Address.hostAddress!!, 32)
        builder.addAddress(FakeDnsPool.Ipv6Address.hostAddress!!, 128)
        if (config.forwarding) {
            // hev 的映射 DNS：在本地回答，連線以域名送給上游代理。
            builder.addDnsServer(ForwardingDefaults.MapDnsAddress.hostAddress!!)
        } else {
            builder.addDnsServer(FakeDnsPool.DnsServer.hostAddress!!)
        }
        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("::", 0)
        builder.setMetered(false)
        builder.setBlocking(true)
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        config.bypassPackages.forEach { packageName ->
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { error ->
                    com.networkcontrol.android.kotlin.platform.AppLog.w(
                        "vpn",
                        "cannot exclude $packageName: ${error.message}",
                    )
                }
        }
        return builder.establish()
    }

    internal fun ensureForeground(id: Int, notification: Notification) {
        if (foreground) {
            updateForeground(id, notification)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(id, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(id, notification)
        }
        foreground = true
    }

    internal fun updateForeground(id: Int, notification: Notification) {
        if (!foreground) return
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)?.notify(id, notification)
        }
    }

    internal fun shutdown() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.networkcontrol.android.kotlin.action.START_VPN"
        const val SESSION_NAME = "NetworkControl KT"
    }
}
