package com.networkcontrol.android.automation.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import com.networkcontrol.android.automation.service.GkdAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** 透過 Shizuku / root 切換無障礙服務的結果。 */
sealed interface AccessibilityGrantOutcome {
    data object Enabled : AccessibilityGrantOutcome
    data object Disabled : AccessibilityGrantOutcome
    data object NoTool : AccessibilityGrantOutcome
    data object Denied : AccessibilityGrantOutcome
    data class Failed(val message: String) : AccessibilityGrantOutcome
}

/**
 * 無障礙服務狀態查詢、系統設定跳轉，以及 Shizuku / root 輔助一鍵開關。
 *
 * 讀寫 `settings secure enabled_accessibility_services` 時會嚴格保留其他已啟用的無障礙服務，
 * 不會覆蓋使用者原有的其他無障礙工具。
 */
object AccessibilityPrivilegeHelper {
    private const val TAG = "A11yPrivilege"
    private const val SHIZUKU_REQUEST_CODE = 0x4E44

    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context.packageName, GkdAccessibilityService::class.java.name)

    fun serviceComponentFlat(context: Context): String =
        serviceComponent(context).flattenToString()

    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val full = serviceComponent(context).flattenToString()
        val short = serviceComponent(context).flattenToShortString()
        return enabled.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
    }

    fun openAccessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isShizukuRunning(): Boolean =
        runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    /** 透過 Shizuku（無 Shizuku 時退回 root）一鍵開啟或關閉本應用的無障礙服務。 */
    suspend fun setServiceEnabledViaPrivilege(
        context: Context,
        enable: Boolean,
    ): AccessibilityGrantOutcome {
        val targetFull = serviceComponent(context).flattenToString()
        val targetShort = serviceComponent(context).flattenToShortString()
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        val services = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            .filterNot { it.equals(targetFull, ignoreCase = true) || it.equals(targetShort, ignoreCase = true) }
            .toMutableList()

        if (enable) {
            services.add(targetFull)
        }
        val newValue = services.joinToString(":")

        val cmd = if (enable) {
            "settings put secure enabled_accessibility_services '$newValue' && settings put secure accessibility_enabled 1"
        } else {
            if (newValue.isEmpty()) {
                "settings delete secure enabled_accessibility_services"
            } else {
                "settings put secure enabled_accessibility_services '$newValue'"
            }
        }

        val outcome = if (isShizukuRunning()) {
            execWithShizuku(cmd)
        } else {
            execWithRoot(cmd)
        }

        return when (outcome) {
            is ExecResult.Ok -> if (enable) AccessibilityGrantOutcome.Enabled else AccessibilityGrantOutcome.Disabled
            ExecResult.NoTool -> AccessibilityGrantOutcome.NoTool
            ExecResult.Denied -> AccessibilityGrantOutcome.Denied
            is ExecResult.Failed -> AccessibilityGrantOutcome.Failed(outcome.message)
        }
    }

    private sealed interface ExecResult {
        data object Ok : ExecResult
        data object NoTool : ExecResult
        data object Denied : ExecResult
        data class Failed(val message: String) : ExecResult
    }

    private suspend fun execWithShizuku(shellCommand: String): ExecResult {
        val permitted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ||
            requestShizukuPermission()
        if (!permitted) return ExecResult.Denied
        return withContext(Dispatchers.IO) {
            runCatching {
                val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                    ?: return@runCatching ExecResult.Failed("Shizuku service unavailable")
                val process = service.newProcess(arrayOf("sh", "-c", shellCommand), null, null)
                val err = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
                    .bufferedReader().use { it.readText() }.trim()
                val exit = process.waitFor()
                runCatching { process.destroy() }
                Log.i(TAG, "shizuku a11y toggle exit=$exit ${err.take(200)}")
                if (exit == 0) ExecResult.Ok else ExecResult.Failed(err.ifEmpty { "exit $exit" })
            }.getOrElse { ExecResult.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    private suspend fun requestShizukuPermission(): Boolean {
        if (Shizuku.shouldShowRequestPermissionRationale()) return false
        return withTimeoutOrNull(120_000) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode != SHIZUKU_REQUEST_CODE) return
                        Shizuku.removeRequestPermissionResultListener(this)
                        if (continuation.isActive) {
                            continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                        }
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        } ?: false
    }

    private suspend fun execWithRoot(shellCommand: String): ExecResult = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder("su", "-c", shellCommand)
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return@withContext ExecResult.NoTool
        }
        runCatching {
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroy()
                return@runCatching ExecResult.Failed("su timed out")
            }
            val exit = process.exitValue()
            Log.i(TAG, "su a11y toggle exit=$exit ${output.take(200)}")
            when {
                exit == 0 -> ExecResult.Ok
                output.contains("denied", ignoreCase = true) || exit == 1 -> ExecResult.Denied
                else -> ExecResult.Failed(output.ifEmpty { "exit $exit" })
            }
        }.getOrElse { ExecResult.Failed(it.message ?: it.javaClass.simpleName) }
    }
}
