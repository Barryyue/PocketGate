package com.networkcontrol.android.kotlin.engine.traffic

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Process
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.storage.AppStore
import com.networkcontrol.android.kotlin.core.storage.TrafficStore
import com.networkcontrol.android.kotlin.engine.apps.AppCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

class TrafficCollector(
    private val context: Context,
    private val catalog: AppCatalog,
    private val apps: AppStore,
    private val traffic: TrafficStore,
) {
    private val prefs = context.getSharedPreferences("networkcontrol-kotlin-traffic", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NetworkStatsManager::class.java)
    private val gate = kotlinx.coroutines.sync.Mutex()

    private data class Usage(
        val uid: Int,
        val kind: NetworkKind,
        val subscriptionId: Int?,
        val bytesIn: Long,
        val bytesOut: Long,
    )

    val isAvailable: Boolean
        get() = runCatching {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return@runCatching false
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

    fun createAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    suspend fun collectNow() = withContext(Dispatchers.IO) {
        gate.withLock {
            if (!isAvailable) return@withLock
            val now = Time.now()
            val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val since = prefs.getLong(KEY_LAST_COLLECTED, todayStart).let {
                if (it <= 0L || it > now) todayStart else it
            }

            val usages = mutableListOf<Usage>()
            collectType(ConnectivityManager.TYPE_WIFI, null, NetworkKind.Wifi, null, since, now, usages)
            collectMobile(since, now, usages)

            usages.forEach { usage ->
                if (usage.uid < 0) return@forEach
                val packageName = catalog.packagesForUid(usage.uid).firstOrNull() ?: return@forEach
                val app = catalog.find(packageName) ?: return@forEach
                val identity = apps.upsert(catalog.toIdentity(app))
                traffic.add(
                    identity.appId,
                    now,
                    usage.bytesIn,
                    usage.bytesOut,
                    usage.kind,
                    usage.subscriptionId,
                )
            }
            prefs.edit().putLong(KEY_LAST_COLLECTED, now).apply()
        }
    }

    private fun collectMobile(since: Long, now: Long, out: MutableList<Usage>) {
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val infos = runCatching { subscriptionManager?.activeSubscriptionInfoList }.getOrNull().orEmpty()
        var collected = false
        if (infos.isNotEmpty() && telephony != null) {
            infos.forEach { info ->
                val subscriberId = runCatching {
                    telephony.createForSubscriptionId(info.subscriptionId).subscriberId
                }.getOrNull()
                if (!subscriberId.isNullOrBlank()) {
                    collected = collectType(
                        ConnectivityManager.TYPE_MOBILE,
                        subscriberId,
                        NetworkKind.Cellular,
                        info.subscriptionId,
                        since,
                        now,
                        out,
                    ) || collected
                }
            }
        }
        if (!collected) {
            collectType(
                ConnectivityManager.TYPE_MOBILE,
                null,
                NetworkKind.Cellular,
                null,
                since,
                now,
                out,
            )
        }
    }

    private fun collectType(
        networkType: Int,
        subscriberId: String?,
        kind: NetworkKind,
        subscriptionId: Int?,
        since: Long,
        now: Long,
        out: MutableList<Usage>,
    ): Boolean {
        val statsManager = manager ?: return false
        return runCatching {
            statsManager.querySummary(networkType, subscriberId, since, now).use { stats ->
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    if (bucket.uid < 0) continue
                    out += Usage(
                        uid = bucket.uid,
                        kind = kind,
                        subscriptionId = subscriptionId,
                        bytesIn = bucket.rxBytes,
                        bytesOut = bucket.txBytes,
                    )
                }
            }
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val KEY_LAST_COLLECTED = "last_collected"
    }
}
