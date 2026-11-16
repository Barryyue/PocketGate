package com.networkcontrol.android.kotlin.platform.permissions

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import com.networkcontrol.android.kotlin.platform.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** 授予 DUMP（關閉偵測）的結果。 */
sealed interface DumpGrantOutcome {
    data object Granted : DumpGrantOutcome

    /** 沒有正在執行的 Shizuku，也沒有 root：只能引導使用者。 */
    data object NoTool : DumpGrantOutcome

    /** 使用者拒絕了 Shizuku 或 root 的授權請求。 */
    data object Denied : DumpGrantOutcome

    data class Failed(val message: String) : DumpGrantOutcome
}

/**
 * 在不接電腦的情況下替本應用授予 `android.permission.DUMP`：
 * 先用 Shizuku（以 shell 身分執行 `pm grant`），沒有 Shizuku 時嘗試 root（`su -c`）。
 *
 * Shizuku 13 把 `Shizuku.newProcess` 設為私有，這裡直接用公開的 `Shizuku.getBinder()`
 * 取得 `IShizukuService` 呼叫 `newProcess`；服務端同樣會檢查本應用是否已獲 Shizuku 授權。
 */
object DumpPermissionGranter {
    const val PERMISSION = "android.permission.DUMP"
    private const val REQUEST_CODE = 0x4E43

    fun adbCommand(packageName: String): String = "adb shell pm grant $packageName $PERMISSION"

    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    suspend fun grant(packageName: String): DumpGrantOutcome {
        if (shizukuRunning()) return grantWithShizuku(packageName)
        return grantWithRoot(packageName)
    }

    private suspend fun grantWithShizuku(packageName: String): DumpGrantOutcome {
        val permitted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ||
            requestShizukuPermission()
        if (!permitted) return DumpGrantOutcome.Denied
        return withContext(Dispatchers.IO) {
            runCatching {
                val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                    ?: return@runCatching DumpGrantOutcome.Failed("Shizuku service unavailable")
                val process = service.newProcess(arrayOf("pm", "grant", packageName, PERMISSION), null, null)
                val error = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
                    .bufferedReader().use { it.readText() }.trim()
                val exit = process.waitFor()
                runCatching { process.destroy() }
                AppLog.i("permissions", "shizuku pm grant DUMP exit=$exit ${error.take(200)}")
                if (exit == 0) DumpGrantOutcome.Granted else DumpGrantOutcome.Failed(error.ifEmpty { "exit $exit" })
            }.getOrElse { DumpGrantOutcome.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    private suspend fun requestShizukuPermission(): Boolean {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            // 使用者曾在 Shizuku 中選了「拒絕且不再詢問」，Shizuku 不會再彈窗。
            return false
        }
        return withTimeoutOrNull(120_000) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode != REQUEST_CODE) return
                        Shizuku.removeRequestPermissionResultListener(this)
                        if (continuation.isActive) continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } ?: false
    }

    private suspend fun grantWithRoot(packageName: String): DumpGrantOutcome = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder("su", "-c", "pm grant $packageName $PERMISSION")
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return@withContext DumpGrantOutcome.NoTool
        }
        runCatching {
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroy()
                return@runCatching DumpGrantOutcome.Failed("su timed out")
            }
            val exit = process.exitValue()
            AppLog.i("permissions", "su pm grant DUMP exit=$exit ${output.take(200)}")
            when {
                exit == 0 -> DumpGrantOutcome.Granted
                // su 存在但使用者拒絕（Magisk / KernelSU 通常回傳 1 並輸出 permission denied）
                output.contains("denied", ignoreCase = true) || exit == 1 -> DumpGrantOutcome.Denied
                else -> DumpGrantOutcome.Failed(output.ifEmpty { "exit $exit" })
            }
        }.getOrElse { DumpGrantOutcome.Failed(it.message ?: it.javaClass.simpleName) }
    }
}
