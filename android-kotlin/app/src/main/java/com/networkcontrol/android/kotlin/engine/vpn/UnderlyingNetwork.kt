package com.networkcontrol.android.kotlin.engine.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * 找出目前的實體網路（非 VPN），供內建直連出口解析域名與綁定 socket。
 * 結果緩存 2 秒，避免每條連線都做 Binder 呼叫；網路切換後最多 2 秒內跟上。
 */
class UnderlyingNetwork(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var cached: Network? = null

    @Volatile
    private var cachedAt = 0L

    fun current(): Network? {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < CACHE_MS) return cached
        val manager = connectivity ?: return null
        val network = runCatching {
            val candidates = buildList {
                manager.activeNetwork?.let(::add)
                @Suppress("DEPRECATION")
                addAll(manager.allNetworks)
            }
            candidates.firstOrNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrNull()
        cached = network
        cachedAt = now
        return network
    }

    fun resolve(host: String): Array<InetAddress> =
        current()?.getAllByName(host) ?: InetAddress.getAllByName(host)

    /** 在實體網路上開啟連線（本應用被排除在 VPN 之外，但明確綁定可以避開 VPN 的 DNS 設定）。 */
    fun openConnection(url: java.net.URL): java.net.URLConnection =
        current()?.openConnection(url) ?: url.openConnection()

    /** 目前的實體網路是否不計量（一般是 Wi-Fi / 有線）。查不到時視為計量，避免意外耗用行動數據。 */
    fun isUnmetered(): Boolean {
        val network = current() ?: return false
        val caps = runCatching { connectivity?.getNetworkCapabilities(network) }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun bind(socket: Socket) {
        current()?.bindSocket(socket)
    }

    fun bind(socket: DatagramSocket) {
        current()?.bindSocket(socket)
    }

    private companion object {
        const val CACHE_MS = 2_000L
    }
}
