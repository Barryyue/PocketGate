package com.networkcontrol.android.kotlin.engine.apps

/**
 * 依 UsageStats 事件追蹤「哪些應用目前有 Activity 在前台」，按 Activity 而不是按套件計算：
 * 同一應用內切換頁面時，事件順序是「舊頁 PAUSED → 新頁 RESUMED → 舊頁 STOPPED」，
 * 只看套件會把最後的 STOPPED 誤判成離開應用。
 *
 * 純邏輯、無 Android 依賴，方便單元測試；呼叫端負責同步。
 */
class AppForegroundState {
    private val resumed = HashMap<String, HashSet<String>>()
    private val leftAt = HashMap<String, Long>()
    private var screenOn = true

    /** 目前有 Activity 處於 RESUMED 的應用。 */
    fun isForeground(packageName: String): Boolean = resumed[packageName]?.isNotEmpty() == true

    /** 應用最後一次「所有 Activity 都離開前台」的時間；之後又回到前台時清除。 */
    fun leftAt(packageName: String): Long? = leftAt[packageName]

    fun onResumed(packageName: String, className: String?) {
        resumed.getOrPut(packageName) { HashSet() } += className.orEmpty()
        leftAt.remove(packageName)
    }

    /**
     * ACTIVITY_PAUSED / ACTIVITY_STOPPED。
     * PAUSED 之後可能緊接著同應用另一個頁面的 RESUMED，所以只在 STOPPED 且亮屏時記錄「離開應用」。
     */
    fun onPausedOrStopped(packageName: String, className: String?, timestamp: Long, stopped: Boolean) {
        val set = resumed[packageName]
        set?.remove(className.orEmpty())
        if (set.isNullOrEmpty()) {
            resumed.remove(packageName)
            if (stopped && screenOn) leftAt[packageName] = timestamp
        }
    }

    fun onScreenInteractive(interactive: Boolean) {
        screenOn = interactive
    }

    /** 關屏 / 鎖屏 / 關機：前台清空，但不算使用者離開應用。 */
    fun onForegroundReset() {
        resumed.clear()
    }

    /** 只保留仍有「允許一次」規則的應用的離開記錄。 */
    fun retainLeft(packages: Set<String>) {
        leftAt.keys.retainAll(packages)
    }

    fun clear() {
        resumed.clear()
        leftAt.clear()
        screenOn = true
    }
}
