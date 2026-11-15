package com.networkcontrol.android.kotlin.platform.prompt

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Process

/**
 * 用 UsageStats 增量追蹤目前前台應用的 UID。
 * 只在有待決詢問時由 PromptRouter 呼叫，避免平時輪詢耗電。
 */
class PromptForegroundTracker(private val context: Context) {
    private val appContext = context.applicationContext
    private val usage = appContext.getSystemService(UsageStatsManager::class.java)
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)
    private val packageManager = appContext.packageManager
    private val uidCache = HashMap<String, Int?>()
    private var lastQuery = 0L
    private var currentPackage: String? = null
    private val resumedActivities = LinkedHashMap<String, String>()
    private val excluded = HashSet<String>()
    private var excludedLoaded = false

    private val selfPackage = appContext.packageName

    fun isAvailable(): Boolean = runCatching {
        val appOps = appContext.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appContext.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun isScreenInteractive(): Boolean =
        power?.isInteractive != false

    fun isKeyguardLocked(): Boolean =
        keyguard?.isKeyguardLocked == true

    @Synchronized
    fun foregroundUid(): Int? {
        if (!isAvailable()) return null
        if (!isScreenInteractive() || isKeyguardLocked()) {
            currentPackage = null
            return null
        }
        val manager = usage ?: return null
        loadExcluded()
        val now = System.currentTimeMillis()
        val start = when {
            lastQuery == 0L -> now - 5 * 60 * 1000L
            else -> maxOf(lastQuery - 1_000L, now - 5 * 60 * 1000L)
        }
        lastQuery = now
        runCatching {
            val events = manager.queryEvents(start, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        val packageName = event.packageName
                        if (packageName != null && packageName !in excluded) {
                            val key = activityKey(packageName, event.className)
                            resumedActivities.remove(key)
                            resumedActivities[key] = packageName
                            currentPackage = packageName
                        }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    -> {
                        val packageName = event.packageName
                        if (packageName != null) {
                            resumedActivities.remove(activityKey(packageName, event.className))
                            currentPackage = resumedActivities.values.lastOrNull()
                        }
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_SHOWN,
                    UsageEvents.Event.DEVICE_SHUTDOWN,
                    -> {
                        resumedActivities.clear()
                        currentPackage = null
                    }
                }
            }
        }.onFailure {
            currentPackage = null
        }
        val packageName = currentPackage ?: return null
        return uidForPackage(packageName)
    }

    fun uidForPackage(packageName: String): Int? {
        uidCache[packageName]?.let { return it }
        val uid = runCatching { packageManager.getPackageUid(packageName, 0) }.getOrNull()
        uidCache[packageName] = uid
        return uid
    }

    /** 取得目前前台套件名稱；會順便刷新 UsageStats。 */
    @Synchronized
    fun foregroundPackage(): String? {
        foregroundUid()
        return currentPackage
    }

    private fun activityKey(packageName: String, className: String?): String =
        "$packageName/${className.orEmpty()}"

    private fun loadExcluded() {
        if (excludedLoaded) return
        excludedLoaded = true
        excluded += selfPackage
        excluded += "com.android.systemui"
        excluded += "android"
        excluded += "com.android.permissioncontroller"
        excluded += "com.google.android.permissioncontroller"
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(intent, 0).forEach {
                excluded += it.activityInfo.packageName
            }
        }
    }
}
