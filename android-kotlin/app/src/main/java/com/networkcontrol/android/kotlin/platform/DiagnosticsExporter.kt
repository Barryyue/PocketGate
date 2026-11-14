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

class DiagnosticsExporter(
    private val context: Context,
    private val container: AppContainer,
) {
    fun export(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val target = File(directory, "NetworkControlKT-diagnostics-$stamp.zip")
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putText(
                "summary.txt",
                buildString {
                    appendLine("NetworkControl KT")
                    appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("protection=${container.vpnEngine.status()}")
                    appendLine("tunnel=${container.vpnEngine.tunnelState.value}")
                    appendLine("mode=${container.runtime.mode}")
                    appendLine("profile=${container.profiles.active.name}")
                    appendLine("rules=${container.rules.ruleSet.value.count}")
                    appendLine("apps=${container.appCatalog.getApps().size}")
                    appendLine("work_profile=${container.backend.isWorkProfile()}")
                    appendLine("network=${container.network.current.displayName.orEmpty()} " +
                        "kind=${container.network.current.kind} ssid=${container.network.current.ssid.orEmpty()} " +
                        "metered=${container.network.current.isMetered}")
                    appendLine("sim=${container.network.current.subscriptionId ?: -1} " +
                        "slot=${container.network.current.simSlotIndex ?: -1}")
                    appendLine("private_dns=${container.backend.privateDnsMode()}")
                    appendLine("ipv6=" + runCatching {
                        java.net.NetworkInterface.getNetworkInterfaces().toList()
                            .filter { it.isUp && !it.isLoopback }
                            .flatMap { it.inetAddresses.toList() }
                            .filterIsInstance<java.net.Inet6Address>()
                            .map { it.hostAddress }
                            .take(5)
                            .joinToString(",")
                    }.getOrDefault(""))
                    appendLine("lock_screen_block=${container.preferences.blockOnLockScreen}")
                    appendLine("focus=${container.focus.active() != null}")
                    appendLine("overlay_allowed=${container.promptOverlay.canDrawOverlays()}")
                    appendLine("usage_stats=${container.trafficCollector.isAvailable}")
                    appendLine("bypass=${container.vpnEngine.plan.value.bypassPackages.size}")
                    appendLine("skipped_rules=${container.vpnEngine.plan.value.skippedRules.size}")
                    appendLine("traffic_access=${container.trafficCollector.isAvailable}")
                    appendLine("packets=${container.vpnEngine.counters.packets.get()}")
                    appendLine("attempts=${container.vpnEngine.counters.attempts.get()}")
                    appendLine("prompts=${container.vpnEngine.counters.reported.get()}")
                    appendLine("dns_answered=${container.vpnEngine.counters.dnsAnswered.get()}")
                    appendLine("unknown_owner=${container.vpnEngine.counters.unknownOwner.get()}")
                    appendLine("resets=${container.vpnEngine.counters.resetsSent.get()}")
                    appendLine("unreachables=${container.vpnEngine.counters.unreachablesSent.get()}")
                    appendLine("error=${container.vpnEngine.tunnelError.value.orEmpty()}")
                },
            )
            zip.putText("rules.json", runCatching { container.backend.exportRules() }.getOrElse { it.message.orEmpty() })
            zip.putText("plan.txt", buildString {
                val plan = container.vpnEngine.plan.value
                plan.byUid.values.sortedBy { it.packageName }.forEach {
                    appendLine("${it.packageName} uid=${it.uid} ${it.decision} ${it.reason}")
                }
                plan.skippedRules.forEach { appendLine("skipped ${it.ruleId} ${it.reason}") }
            })
            copyIfExists(zip, "config.db", context.getDatabasePath(com.networkcontrol.android.kotlin.data.db.ConfigDatabase.NAME))
            copyIfExists(zip, "activity.db", context.getDatabasePath(com.networkcontrol.android.kotlin.data.db.ActivityDatabase.NAME))
            val logs = File(context.filesDir, "logs")
            logs.listFiles()?.sortedByDescending { it.name }?.take(5)?.forEach { file ->
                copyIfExists(zip, "logs/${file.name}", file)
            }
        }
        return target
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun copyIfExists(zip: ZipOutputStream, name: String, file: File) {
        if (!file.isFile) return
        runCatching {
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
