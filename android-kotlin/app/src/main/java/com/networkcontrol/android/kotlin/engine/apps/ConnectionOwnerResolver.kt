package com.networkcontrol.android.kotlin.engine.apps

import android.content.Context
import android.net.ConnectivityManager
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import java.net.InetSocketAddress

class ConnectionOwnerResolver(context: Context) {
    private val manager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    /** 必須在後台線程調用；getConnectionOwnerUid 是 Binder 調用。 */
    fun getOwnerUid(protocol: L4Protocol, local: InetSocketAddress, remote: InetSocketAddress): Int? {
        val cm = manager ?: return null
        return runCatching {
            cm.getConnectionOwnerUid(protocol.number, local, remote)
        }.getOrNull()
    }
}
