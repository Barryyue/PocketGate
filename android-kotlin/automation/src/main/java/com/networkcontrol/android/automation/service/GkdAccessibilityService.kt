package com.networkcontrol.android.automation.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.networkcontrol.android.automation.engine.AccessibilityNodeAdapter
import com.networkcontrol.android.automation.engine.CompiledRule
import com.networkcontrol.android.automation.engine.RuleEngine
import com.networkcontrol.android.automation.model.RawPosition
import com.networkcontrol.android.automation.storage.AutomationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 運行於獨立進程 `:automation` 的無障礙自動跳過服務。
 *
 * - 與主進程 VPN / UI 進程完全隔離，崩潰或被系統回收不會連帶影響 VPN 保護。
 * - 透過 `automation.db`（WAL 模式）讀取訂閱與開關，並監聽顯式包名廣播重載規則。
 */
class GkdAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: AutomationStore

    @Volatile
    private var engine: RuleEngine = RuleEngine(emptyList(), emptyList())

    @Volatile
    private var featureEnabled: Boolean = false

    @Volatile
    private var lastAppId: String? = null

    @Volatile
    private var lastActivityId: String? = null

    private var pendingEvalJob: Job? = null

    private val reloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RELOAD_RULES) {
                reloadRulesAsync()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = AutomationStore(applicationContext)
        registerReloadReceiver()
        reloadRulesAsync()
        Log.i(TAG, "GkdAccessibilityService connected in isolated process")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !featureEnabled) return
        val pkg = event.packageName?.toString()?.takeIf { it.isNotEmpty() } ?: return
        // 不處理本應用自身與系統 UI 畫面，避免誤操作設定介面或狀態列
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return

        val now = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString()?.takeIf { it.contains('.') }
            if (pkg != lastAppId || (cls != null && cls != lastActivityId)) {
                lastAppId = pkg
                if (cls != null) lastActivityId = cls
                engine.onForegroundChanged(pkg, lastActivityId, now)
            }
        }

        // O(1) 檢查：若該應用無任何生效規則，直接秒返，不取 rootInActiveWindow
        if (!engine.hasRulesForApp(pkg)) return

        // 50ms 節流，避免短時間大量 CONTENT_CHANGED 事件塞爆 CPU
        pendingEvalJob?.cancel()
        pendingEvalJob = scope.launch {
            delay(50L)
            evaluateCurrentWindow(pkg, lastActivityId)
        }
    }

    override fun onInterrupt() {
        pendingEvalJob?.cancel()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(reloadReceiver) }
        pendingEvalJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun evaluateCurrentWindow(appId: String, activityId: String?) {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val rootPkg = root.packageName?.toString()?.takeIf { it.isNotEmpty() } ?: return
        // 核心防護：狀態列/系統元件事件可能帶其他包名，但 rootInActiveWindow 仍是本應用；
        // 必須以活躍視窗真實包名為準，絕不對本應用自身視窗或跨包名視窗執行匹配。
        if (rootPkg == packageName || rootPkg != appId) return
        val adapter = AccessibilityNodeAdapter(rootPkg)
        val now = System.currentTimeMillis()
        val matched = runCatching {
            engine.evaluate(
                appId = appId,
                activityId = activityId,
                rootNode = root,
                adapter = adapter,
                nowMs = now,
            )
        }.getOrNull() ?: return

        scope.launch {
            if (matched.rule.actionDelayMs > 0) {
                delay(matched.rule.actionDelayMs)
            }
            val performed = executeAction(matched.rule, matched.targetNode)
            if (performed) {
                runCatching {
                    store.insertTriggerLog(
                        subscriptionId = matched.rule.subscriptionId,
                        subscriptionName = matched.rule.subscriptionName,
                        appId = appId,
                        activityId = activityId,
                        groupKey = matched.rule.groupKey,
                        groupName = matched.rule.groupName,
                        ruleKey = matched.rule.ruleKey,
                        action = matched.rule.action,
                    )
                }
            }
        }
    }

    private fun executeAction(rule: CompiledRule, node: AccessibilityNodeInfo): Boolean {
        return when (rule.action) {
            "none" -> true
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "longClick" -> performNodeOrGesture(node, rule.position, longPress = true, forceGesture = false)
            "clickNode" -> clickClickableAncestor(node)
            "clickCenter" -> performNodeOrGesture(node, rule.position, longPress = false, forceGesture = true)
            else -> {
                // 預設 "click"：若有指定 position 則直接手勢點擊；否則先試節點點擊再退回座標點擊
                if (rule.position != null) {
                    performNodeOrGesture(node, rule.position, longPress = false, forceGesture = true)
                } else {
                    clickClickableAncestor(node) ||
                        performNodeOrGesture(node, null, longPress = false, forceGesture = true)
                }
            }
        }
    }

    private fun clickClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun performNodeOrGesture(
        node: AccessibilityNodeInfo,
        position: RawPosition?,
        longPress: Boolean,
        forceGesture: Boolean,
    ): Boolean {
        if (!forceGesture && !longPress && position == null) {
            if (clickClickableAncestor(node)) return true
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false

        val (x, y) = resolveClickPoint(rect, position)
        if (x < 0f || y < 0f) return false

        val path = Path().apply { moveTo(x, y) }
        val duration = if (longPress) 550L else 40L
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun resolveClickPoint(rect: Rect, position: RawPosition?): Pair<Float, Float> {
        if (position == null) {
            return rect.exactCenterX() to rect.exactCenterY()
        }
        val x = parseOffset(position.left, rect.width())?.let { rect.left + it }
            ?: parseOffset(position.right, rect.width())?.let { rect.right - it }
            ?: rect.exactCenterX()
        val y = parseOffset(position.top, rect.height())?.let { rect.top + it }
            ?: parseOffset(position.bottom, rect.height())?.let { rect.bottom - it }
            ?: rect.exactCenterY()
        return x to y
    }

    /** 支援純數字（像素）與簡易百分比 / `width * 0.5` 表達式。 */
    private fun parseOffset(expr: String?, span: Int): Float? {
        val raw = expr?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toFloatOrNull()?.let { return it }
        if (raw.endsWith("%")) {
            val pct = raw.removeSuffix("%").trim().toFloatOrNull() ?: return null
            return span * (pct / 100f)
        }
        val mulIdx = raw.indexOf('*')
        if (mulIdx > 0) {
            val right = raw.substring(mulIdx + 1).trim().toFloatOrNull() ?: return null
            return span * right
        }
        return null
    }

    private var reloadJob: kotlinx.coroutines.Job? = null

    private fun reloadRulesAsync() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            kotlinx.coroutines.delay(250L)
            runCatching {
                featureEnabled = store.featureEnabled
                if (!featureEnabled) {
                    engine = RuleEngine(emptyList(), emptyList())
                    Log.i(TAG, "automation feature disabled; cleared rules")
                    return@launch
                }
                store.ensureDefaultSubscriptionSeeded()
                val subs = store.loadAllEnabledSubscriptions()
                val overrides = store.listGroupOverrides()
                engine = RuleEngine(subs, overrides)
                Log.i(TAG, "reloaded ${subs.size} subscriptions")
            }.onFailure {
                Log.w(TAG, "failed to reload automation rules", it)
            }
        }
    }

    private fun registerReloadReceiver() {
        val filter = IntentFilter(ACTION_RELOAD_RULES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(reloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(reloadReceiver, filter)
        }
    }

    companion object {
        private const val TAG = "GkdA11y"
        const val ACTION_RELOAD_RULES = "com.networkcontrol.android.automation.ACTION_RELOAD_RULES"

        /** 主進程修改開關或訂閱後呼叫，通知 `:automation` 進程即時重載。 */
        fun notifyReload(context: Context) {
            val intent = Intent(ACTION_RELOAD_RULES).setPackage(context.packageName)
            context.sendBroadcast(intent)
        }
    }
}
