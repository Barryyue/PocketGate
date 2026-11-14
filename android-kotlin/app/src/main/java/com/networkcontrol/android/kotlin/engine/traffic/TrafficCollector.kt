package com.networkcontrol.android.kotlin.engine.traffic

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Process
import android.provider.Settings
import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.AppIdentity
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

            val totals = HashMap<Int, Pair<Long, Long>>()
            collectType(ConnectivityManager.TYPE_WIFI, since, now, totals)
            collectType(ConnectivityManager.TYPE_MOBILE, since, now, totals)

            totals.forEach { (uid, usage) ->
                if (uid < 0) return@forEach
                val packageName = catalog.packagesForUid(uid).firstOrNull() ?: return@forEach
                val app = catalog.find(packageName) ?: return@forEach
                val identity = apps.upsert(catalog.toIdentity(app))
                traffic.add(identity.appId, now, usage.first, usage.second)
            }
            prefs.edit().putLong(KEY_LAST_COLLECTED, now).apply()
        }
    }

    private fun collectType(
        networkType: Int,
        since: Long,
        now: Long,
        totals: MutableMap<Int, Pair<Long, Long>>,
    ) {
        val statsManager = manager ?: return
        runCatching {
            val stats = statsManager.querySummary(networkType, null, since, now)
            stats.use {
                val bucket = NetworkStats.Bucket()
                while (it.hasNextBucket()) {
                    it.getNextBucket(bucket)
                    val uid = bucket.uid
                    if (uid < 0) continue
                    val current = totals[uid] ?: (0L to 0L)
                    totals[uid] = (current.first + bucket.rxBytes) to (current.second + bucket.txBytes)
                }
            }
        }
    }

    private companion object {
        const val KEY_LAST_COLLECTED = "last_collected"
    }
}
