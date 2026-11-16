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
    private val uidCache = HashMap<String, Int>()
    private var lastQuery = 0L
    private var currentPackage: String? = null
    private val resumedActivities = LinkedHashMap<String, String>()
    private val excluded = HashSet<String>()
    private var excludedLoaded = false

    /** 按 Activity 追蹤每個應用是否在前台、何時離開（與「僅一次」相同的判斷）。 */
    private val sessions = com.networkcontrol.android.kotlin.engine.apps.AppForegroundState()
    private var sessionsInitialized = false

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
        if (!sessionsInitialized) {
            sessionsInitialized = true
            sessions.onScreenInteractive(isScreenInteractive())
        }
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
                        event.packageName?.let { sessions.onResumed(it, event.className) }
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
                        event.packageName?.let {
                            sessions.onPausedOrStopped(
                                it,
                                event.className,
                                event.timeStamp,
                                stopped = event.eventType == UsageEvents.Event.ACTIVITY_STOPPED,
                            )
                        }
                        val packageName = event.packageName
                        if (packageName != null) {
                            resumedActivities.remove(activityKey(packageName, event.className))
                            currentPackage = resumedActivities.values.lastOrNull()
                        }
                    }
                    UsageEvents.Event.SCREEN_INTERACTIVE -> sessions.onScreenInteractive(true)
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_SHOWN,
                    UsageEvents.Event.DEVICE_SHUTDOWN,
                    -> {
                        if (event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) sessions.onScreenInteractive(false)
                        sessions.onForegroundReset()
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
        val uid = runCatching { packageManager.getPackageUid(packageName, 0) }.getOrNull() ?: return null
        if (uidCache.size >= 512) uidCache.clear()
        uidCache[packageName] = uid
        return uid
    }

    /**
     * 應用最後一次「所有頁面都離開前台」的時間（亮屏時才算）；之後又回到前台則為 null。
     * 應用內換頁、彈出權限對話框、關屏都不算離開。呼叫前先用 [foregroundPackage] 刷新事件。
     */
    @Synchronized
    fun leftAt(packageName: String): Long? = sessions.leftAt(packageName)

    /** 應用目前是否有頁面在前台。呼叫前先用 [foregroundPackage] 刷新事件。 */
    @Synchronized
    fun isForeground(packageName: String): Boolean = sessions.isForeground(packageName)

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
