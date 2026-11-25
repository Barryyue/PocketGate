package com.networkcontrol.android.kotlin.platform

import android.content.Context
import android.os.Build
import com.networkcontrol.android.kotlin.AppContainer
import com.networkcontrol.android.kotlin.BuildConfig
import com.networkcontrol.android.kotlin.core.model.FilterMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticsRedactor {
    private val urlCredsRegex = Regex("(https?://)([^/@\\s]+@)", RegexOption.IGNORE_CASE)
    private val secretKvRegex = Regex(
        "(?i)(password|passphrase|passwd|token|authorization|secret)(\\s*[:=]\\s*)([^\\s,;\"']+)",
    )

    fun redactSsid(ssid: String?): String {
        val clean = ssid?.trim().orEmpty()
        return if (clean.isEmpty()) "" else "<redacted(len=${clean.length})>"
    }

    fun redactIpv6(hostAddress: String?): String {
        val raw = hostAddress?.substringBefore('%')?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val lower = raw.lowercase(Locale.US)
        if (lower == "::1" || lower.startsWith("fe80:") || lower.startsWith("fd00:6e63:")) {
            return raw
        }
        val parts = raw.split(':')
        return if (parts.size >= 2) "${parts[0]}:${parts[1]}::/32(redacted)" else "<redacted-ipv6>"
    }

    fun redactLogLine(line: String): String {
        var out = urlCredsRegex.replace(line, "$1***@")
        out = secretKvRegex.replace(out, "$1$2<redacted>")
        return out
    }
}

class DiagnosticsExporter(
    private val context: Context,
    private val container: AppContainer,
) {
    fun export(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val target = File(directory, "PocketGate-diagnostics-$stamp.zip")
        val configDb = context.getDatabasePath(com.networkcontrol.android.kotlin.data.db.ConfigDatabase.NAME)
        val activityDb = context.getDatabasePath(com.networkcontrol.android.kotlin.data.db.ActivityDatabase.NAME)
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putText(
                "summary.txt",
                buildString {
                    appendLine("PocketGate (redacted diagnostics)")
                    appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("protection=${container.vpnEngine.status()}")
                    appendLine("tunnel=${container.vpnEngine.tunnelState.value}")
                    appendLine("mode=${container.runtime.mode}")
                    appendLine("profile_count=${container.profiles.all.size}")
                    appendLine("rules=${container.rules.ruleSet.value.count}")
                    appendLine("apps=${container.appCatalog.getApps().size}")
                    appendLine("work_profile=${container.backend.isWorkProfile()}")
                    appendLine("offline_mode=${container.preferences.offlineMode}")
                    appendLine("forwarding=${container.preferences.forwardingEnabled} upstream=${container.preferences.forwardingUpstream}")
                    appendLine("network_kind=${container.network.current.kind} " +
                        "ssid=${DiagnosticsRedactor.redactSsid(container.network.current.ssid)} " +
                        "metered=${container.network.current.isMetered}")
                    appendLine("sim=${container.network.current.subscriptionId ?: -1} " +
                        "slot=${container.network.current.simSlotIndex ?: -1}")
                    appendLine("private_dns=${container.backend.privateDnsMode()}")
                    appendLine("ipv6=" + runCatching {
                        java.net.NetworkInterface.getNetworkInterfaces().toList()
                            .filter { it.isUp && !it.isLoopback }
                            .flatMap { it.inetAddresses.toList() }
                            .filterIsInstance<java.net.Inet6Address>()
                            .map { DiagnosticsRedactor.redactIpv6(it.hostAddress) }
                            .distinct()
                            .take(5)
                            .joinToString(",")
                    }.getOrDefault(""))
                    appendLine("lock_screen_block=${container.preferences.blockOnLockScreen}")
                    appendLine("focus=${container.focus.active() != null}")
                    appendLine("overlay_allowed=${container.promptOverlay.canDrawOverlays()}")
                    appendLine("usage_stats=${container.trafficCollector.isAvailable}")
                    appendLine("bypass=${container.vpnEngine.plan.value.bypassPackages.size}")
                    appendLine("skipped_rules=${container.vpnEngine.plan.value.skippedRules.size}")
                    appendLine("config_db_bytes=${if (configDb.isFile) configDb.length() else 0}")
                    appendLine("activity_db_bytes=${if (activityDb.isFile) activityDb.length() else 0}")
                    appendLine("packets=${container.vpnEngine.counters.packets.get()}")
                    appendLine("attempts=${container.vpnEngine.counters.attempts.get()}")
                    appendLine("prompts=${container.vpnEngine.counters.reported.get()}")
                    appendLine("dns_answered=${container.vpnEngine.counters.dnsAnswered.get()}")
                    appendLine("unknown_owner=${container.vpnEngine.counters.unknownOwner.get()}")
                    appendLine("resets=${container.vpnEngine.counters.resetsSent.get()}")
                    appendLine("unreachables=${container.vpnEngine.counters.unreachablesSent.get()}")
                    appendLine("error=${DiagnosticsRedactor.redactLogLine(container.vpnEngine.tunnelError.value.orEmpty())}")
                },
            )
            // 僅匯出規則統計摘要，不打包原始 activity.db / config.db（避免洩漏完整瀏覽歷史、網域與私密規則備註）。
            zip.putText("rules-summary.txt", buildString {
                val allRules = container.rules.ruleSet.value.rules
                appendLine("total=${allRules.size}")
                appendLine("enabled=${allRules.count { it.enabled }}")
                appendLine("allow=${allRules.count { it.action == com.networkcontrol.android.kotlin.core.model.VerdictAction.Allow }}")
                appendLine("deny=${allRules.count { it.action == com.networkcontrol.android.kotlin.core.model.VerdictAction.Deny }}")
                appendLine("global=${allRules.count { it.app == null }}")
                appendLine("conditional=${allRules.count { !com.networkcontrol.android.kotlin.engine.vpn.VpnRoutingPlanner.isEnforceable(it) }}")
            })
            zip.putText("plan.txt", buildString {
                val plan = container.vpnEngine.plan.value
                plan.byUid.values.sortedBy { it.packageName }.forEach {
                    appendLine("${it.packageName} uid=${it.uid} ${it.decision} ${it.reason}")
                }
                plan.skippedRules.forEach { appendLine("skipped ${it.ruleId} ${it.reason}") }
            })
            val logs = File(context.filesDir, "logs")
            logs.listFiles()?.sortedByDescending { it.name }?.take(5)?.forEach { file ->
                copyRedactedLog(zip, "logs/${file.name}", file)
            }
        }
        return target
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun copyRedactedLog(zip: ZipOutputStream, name: String, file: File) {
        if (!file.isFile) return
        runCatching {
            zip.putNextEntry(ZipEntry(name))
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                val writer = zip.bufferedWriter(Charsets.UTF_8)
                lines.forEach { line ->
                    writer.write(DiagnosticsRedactor.redactLogLine(line))
                    writer.newLine()
                }
                writer.flush()
            }
            zip.closeEntry()
        }
    }
}
