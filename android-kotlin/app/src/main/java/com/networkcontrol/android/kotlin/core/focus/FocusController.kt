package com.networkcontrol.android.kotlin.core.focus

import com.networkcontrol.android.kotlin.AppPreferences
import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.FocusSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 專注模式：在指定時間內阻止所有應用，只放行所選應用。 */
class FocusController(
    private val preferences: AppPreferences,
    private val scope: CoroutineScope,
) {
    private val _session = MutableStateFlow(load())
    val session: StateFlow<FocusSession?> = _session.asStateFlow()

    /** 專注會話自然到期或手動結束時的回呼（傳入已結束的會話與持續分鐘數）。 */
    var onSessionEnded: ((FocusSession, Int) -> Unit)? = null

    init {
        if (_session.value?.isActive(Time.now()) == false) {
            clear()
        }
        // 事件驅動到期：未開啟專注時完全掛起零喚醒；開啟後精確睡到 endsAt 自動結束。
        scope.launch {
            _session.collectLatest { current ->
                if (current == null) return@collectLatest
                val remaining = current.endsAt - Time.now()
                if (remaining > 0) delay(remaining)
                if (_session.value == current && !current.isActive(Time.now())) {
                    clear()
                }
            }
        }
    }

    fun active(now: Long = Time.now()): FocusSession? =
        _session.value?.takeIf { it.isActive(now) }

    fun start(minutes: Int, allowedPackages: Set<String>): FocusSession {
        val now = Time.now()
        return startUntil(now + minutes.coerceIn(1, 24 * 60) * 60_000L, allowedPackages)
    }

    fun startUntil(endsAt: Long, allowedPackages: Set<String>): FocusSession {
        val now = Time.now()
        val session = FocusSession(
            startedAt = now,
            endsAt = maxOf(endsAt, now + 60_000L),
            allowedPackages = allowedPackages,
        )
        _session.value = session
        preferences.focusSessionJson = toJson(session)
        return session
    }

    fun extend(minutes: Int): FocusSession? {
        val current = active() ?: return null
        val session = current.copy(
            endsAt = maxOf(current.endsAt, Time.now()) + minutes.coerceIn(1, 24 * 60) * 60_000L,
        )
        _session.value = session
        preferences.focusSessionJson = toJson(session)
        return session
    }

    fun stop() {
        val ended = _session.value
        clear()
        if (ended != null) {
            val mins = ((Time.now() - ended.startedAt) / 60_000L).toInt().coerceAtLeast(1)
            onSessionEnded?.invoke(ended, mins)
        }
    }

    private fun clear() {
        val previous = _session.value
        val expired = previous != null && !previous.isActive(Time.now())
        _session.value = null
        preferences.focusSessionJson = null
        if (expired) {
            val mins = ((previous.endsAt - previous.startedAt) / 60_000L).toInt().coerceAtLeast(1)
            onSessionEnded?.invoke(previous, mins)
        }
    }

    private fun load(): FocusSession? {
        val raw = preferences.focusSessionJson ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            FocusSession(
                startedAt = obj.optLong("startedAt"),
                endsAt = obj.optLong("endsAt"),
                allowedPackages = obj.optJSONArray("allowedPackages")
                    ?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                    ?: emptySet(),
            )
        }.getOrNull()
    }

    private fun toJson(session: FocusSession): String = JSONObject().apply {
        put("startedAt", session.startedAt)
        put("endsAt", session.endsAt)
        put("allowedPackages", JSONArray(session.allowedPackages.toList()))
    }.toString()
}
