package com.networkcontrol.android.kotlin.engine.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.networkcontrol.android.kotlin.core.model.NetworkContext
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(private val context: Context) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(NetworkContext.None)
    val state: StateFlow<NetworkContext> = _state.asStateFlow()
    val current: NetworkContext get() = _state.value

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            update(network, capabilities)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            update(network, cm?.getNetworkCapabilities(network))
        }

        override fun onLost(network: Network) {
            _state.value = NetworkContext.None
        }
    }

    fun start() {
        val manager = cm ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
    }

    fun stop() {
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    private fun update(network: Network, capabilities: NetworkCapabilities?) {
        val caps = capabilities ?: return
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Wired
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkKind.Vpn
            else -> NetworkKind.Other
        }
        val ssid = if (kind == NetworkKind.Wifi) currentSsid() else null
        _state.value = NetworkContext(
            kind = kind,
            ssid = ssid,
            networkId = network.networkHandle.toString(),
            displayName = when (kind) {
                NetworkKind.Wifi -> ssid ?: "Wi-Fi"
                NetworkKind.Cellular -> "行動網路"
                NetworkKind.Wired -> "有線網路"
                NetworkKind.Vpn -> "VPN"
                NetworkKind.Other -> "其他網路"
                NetworkKind.None -> null
            },
        )
    }

    private fun currentSsid(): String? {
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            val raw = wifi.connectionInfo?.ssid ?: return null
            raw.trim('"').takeIf { it.isNotBlank() && !it.contains("<unknown", ignoreCase = true) }
        }.getOrNull()
    }
}
