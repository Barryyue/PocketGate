package com.networkcontrol.android.kotlin.core.focus

import com.networkcontrol.android.kotlin.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

data class FocusSchedule(
    val id: String = UUID.randomUUID().toString(),
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    /** 一天中的分鐘數，0..1439。 */
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 18 * 60,
    val enabled: Boolean = true,
) {
    val crossesMidnight: Boolean get() = endMinute <= startMinute
}

/**
 * 按星期與時段自動開始專注模式（不指定允許應用，時段內全部阻止）。
 * 每 30 秒檢查一次；已有手動專注時不覆蓋。
 */
class FocusScheduler(
    private val preferences: AppPreferences,
    private val focus: FocusController,
    private val scope: CoroutineScope,
) {
    private val _schedules = MutableStateFlow(load())
    val schedules: StateFlow<List<FocusSchedule>> = _schedules.asStateFlow()

    init {
        // 沒有啟用的專注時間表時完全掛起零喚醒；有啟用時對齊每分鐘整點檢查一次。
        scope.launch {
            _schedules.collectLatest { list ->
                if (list.none { it.enabled }) return@collectLatest
                while (isActive) {
                    tick()
                    val now = ZonedDateTime.now()
                    val waitMs = ((60 - now.second) * 1000L - now.nano / 1_000_000L).coerceIn(1_000L, 60_000L)
                    delay(waitMs)
                }
            }
        }
    }

    fun replace(schedule: FocusSchedule) {
        val updated = _schedules.value.toMutableList()
        val index = updated.indexOfFirst { it.id == schedule.id }
        if (index >= 0) updated[index] = schedule else updated += schedule
        save(updated)
    }

    fun remove(id: String) {
        save(_schedules.value.filterNot { it.id == id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val found = _schedules.value.firstOrNull { it.id == id } ?: return
        replace(found.copy(enabled = enabled))
    }

    fun tick(now: ZonedDateTime = ZonedDateTime.now()) {
        if (focus.active() != null) return
        val minute = now.hour * 60 + now.minute
        val day = now.dayOfWeek.value
        val match = _schedules.value.firstOrNull { schedule ->
            schedule.enabled && day in schedule.days && schedule.contains(minute)
        } ?: return
        val end = endTime(now, match)
        focus.startUntil(end.toInstant().toEpochMilli(), emptySet())
        com.networkcontrol.android.kotlin.platform.AppLog.i(
            "focus",
            "schedule started id=${match.id} until=$end",
        )
    }

    private fun endTime(now: ZonedDateTime, schedule: FocusSchedule): ZonedDateTime {
        val time = LocalTime.of(schedule.endMinute / 60, schedule.endMinute % 60)
        val date = if (schedule.crossesMidnight && now.hour * 60 + now.minute >= schedule.startMinute) {
            now.toLocalDate().plusDays(1)
        } else {
            now.toLocalDate()
        }
        return date.atTime(time).atZone(now.zone)
    }

    private fun save(schedules: List<FocusSchedule>) {
        _schedules.value = schedules
        preferences.focusSchedulesJson = JSONArray().apply {
            schedules.forEach { schedule ->
                put(
                    JSONObject().apply {
                        put("id", schedule.id)
                        put("days", JSONArray(schedule.days.sorted()))
                        put("startMinute", schedule.startMinute)
                        put("endMinute", schedule.endMinute)
                        put("enabled", schedule.enabled)
                    },
                )
            }
        }.toString()
    }

    private fun load(): List<FocusSchedule> {
        val raw = preferences.focusSchedulesJson ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                FocusSchedule(
                    id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    days = obj.optJSONArray("days")
                        ?.let { days -> (0 until days.length()).map { days.getInt(it) }.toSet() }
                        ?: emptySet(),
                    startMinute = obj.optInt("startMinute", 9 * 60).coerceIn(0, 1439),
                    endMinute = obj.optInt("endMinute", 18 * 60).coerceIn(0, 1439),
                    enabled = obj.optBoolean("enabled", true),
                )
            }
        }.getOrDefault(emptyList())
    }
}

internal fun FocusSchedule.contains(minute: Int): Boolean =
    if (crossesMidnight) minute >= startMinute || minute < endMinute
    else minute in startMinute until endMinute
