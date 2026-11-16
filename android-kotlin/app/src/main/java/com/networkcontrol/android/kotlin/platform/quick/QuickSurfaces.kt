package com.networkcontrol.android.kotlin.platform.quick

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import com.networkcontrol.android.kotlin.AppContainer
import com.networkcontrol.android.kotlin.platform.AppIconLoader
import com.networkcontrol.android.kotlin.platform.AppLog
import com.networkcontrol.android.kotlin.widget.NcWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 桌面掛件顯示的資料（一次載入，所有掛件共用）。 */
data class WidgetData(
    val state: QuickState?,
    val blockedToday: Long = 0,
    val trafficAvailable: Boolean = false,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val topApps: List<TopApp> = emptyList(),
    val loadedAt: Long = 0,
) {
    data class TopApp(val packageName: String, val label: String, val bytes: Long, val icon: Bitmap?)

    companion object {
        val Empty = WidgetData(state = null)
    }
}

/**
 * 快捷入口的同步中心：狀態改變時更新桌面掛件、控制中心磁貼與長按捷徑。
 * 統計（今日攔截、流量）沒有事件可訂閱，只在有掛件時定期重新載入。
 */
class QuickSurfaces(private val container: AppContainer) {
    private val context = container.appContext
    private val _data = MutableStateFlow(WidgetData.Empty)
    val data: StateFlow<WidgetData> = _data.asStateFlow()
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 狀態改變（已去抖）後發出；正在顯示的磁貼據此重繪。 */
    val changes: SharedFlow<Unit> = _changes
    private val loadGate = Mutex()
    private var started = false

    fun start() {
        if (started) return
        started = true
        val scope = container.scope
        scope.launch {
            merge(
                container.protection.enabled,
                container.vpnEngine.tunnelState,
                container.runtime.state,
                container.prompts.pending,
                container.focus.session,
            ).collect { trigger.trySend(Unit) }
        }
        scope.launch {
            for (unused in trigger) {
                delay(DEBOUNCE_MS)
                runCatching { refreshNow() }.onFailure { AppLog.w("quick", "refresh failed: ${it.message}") }
            }
        }
        scope.launch {
            while (true) {
                delay(STATS_INTERVAL_MS)
                if (widgetsPlaced()) trigger.trySend(Unit)
            }
        }
    }

    fun refreshSoon() {
        trigger.trySend(Unit)
    }

    /** 掛件第一次繪製前呼叫：資料太舊時同步載入一次。 */
    suspend fun ensureFresh() {
        if (System.currentTimeMillis() - _data.value.loadedAt > STALE_MS) load()
    }

    private suspend fun refreshNow() {
        val state = QuickActions.state(container)
        _changes.tryEmit(Unit)
        QuickShortcuts.publish(context, state)
        QuickTileService.requestRefreshAll(context)
        if (widgetsPlaced()) {
            load()
            NcWidgets.updateAll(context)
        }
    }

    private suspend fun load() = loadGate.withLock {
        val loaded = withContext(Dispatchers.IO) {
            val state = QuickActions.state(container)
            val blocked = runCatching { container.backend.countBlockedToday() }.getOrDefault(0L)
            val trafficAvailable = container.backend.hasTrafficAccess()
            val traffic = if (trafficAvailable) {
                runCatching { container.backend.getTrafficToday() }.getOrNull()
            } else {
                null
            }
            val top = traffic?.byPackage.orEmpty().entries
                .map { (pkg, value) -> pkg to value.first + value.second }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(TOP_APPS)
                .map { (pkg, bytes) ->
                    WidgetData.TopApp(
                        packageName = pkg,
                        label = container.appCatalog.find(pkg)?.label ?: pkg,
                        bytes = bytes,
                        icon = AppIconLoader.loadBitmap(context, pkg)?.let(::shrink),
                    )
                }
            WidgetData(
                state = state,
                blockedToday = blocked,
                trafficAvailable = trafficAvailable,
                bytesIn = traffic?.bytesIn ?: 0,
                bytesOut = traffic?.bytesOut ?: 0,
                topApps = top,
                loadedAt = System.currentTimeMillis(),
            )
        }
        _data.value = loaded
    }

    /** 掛件的 Bitmap 經 Binder 傳給桌面，縮小避免 TransactionTooLargeException。 */
    private fun shrink(bitmap: Bitmap): Bitmap =
        if (bitmap.width <= ICON_PX && bitmap.height <= ICON_PX) bitmap
        else Bitmap.createScaledBitmap(bitmap, ICON_PX, ICON_PX, true)

    fun widgetsPlaced(): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return NcWidgets.receivers.any { cls ->
            runCatching { manager.getAppWidgetIds(ComponentName(context, cls)).isNotEmpty() }.getOrDefault(false)
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val STATS_INTERVAL_MS = 2 * 60_000L
        const val STALE_MS = 30_000L
        const val TOP_APPS = 5
        const val ICON_PX = 72
    }
}
