package com.networkcontrol.android.kotlin.data.transfer

import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.LogEntry
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 連線記錄 CSV（RFC 4180，UTF-8 BOM）。
 * 欄位順序與桌面版保持一致，方便兩邊互相匯入分析。
 */
object LogCsv {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val zone = ZoneId.systemDefault()

    private val header = listOf(
        "first_seen",
        "last_seen",
        "app_name",
        "app_path",
        "direction",
        "protocol",
        "destination",
        "remote_ip",
        "remote_port",
        "hostname",
        "verdict",
        "reason",
        "rule_id",
        "count",
    )

    fun encode(entries: List<LogEntry>): String = buildString {
        append('\uFEFF')
        append(header.joinToString(",") { escape(it) })
        append("\r\n")
        entries.forEach { entry ->
            append(
                listOf(
                    formatTime(entry.firstTs),
                    formatTime(entry.lastTs),
                    entry.appName.orEmpty(),
                    entry.appPath.orEmpty(),
                    if (entry.direction == Direction.Outbound) "outbound" else "inbound",
                    entry.protocol.name,
                    if (entry.hostname.isNullOrBlank()) "${entry.remoteIp}:${entry.remotePort}" else entry.hostname,
                    entry.remoteIp,
                    entry.remotePort.toString(),
                    entry.hostname.orEmpty(),
                    if (entry.verdict.action == VerdictAction.Allow) "allow" else "deny",
                    entry.verdict.reason.name,
                    entry.verdict.ruleId.orEmpty(),
                    entry.count.toString(),
                ).joinToString(",") { escape(it) },
            )
            append("\r\n")
        }
    }

    private fun formatTime(timestamp: Long): String =
        timeFormat.format(Instant.ofEpochMilli(timestamp).atZone(zone))

    internal fun escape(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in charArrayOf('=', '+', '-', '@', '\t', '\r')) {
            "'$value"
        } else {
            value
        }
        if (safe.indexOfAny(charArrayOf(',', '"', '\r', '\n')) < 0) return safe
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
