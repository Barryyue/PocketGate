package com.networkcontrol.android.kotlin.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 統一拉起系統 APK 安裝器；若尚未授予「安裝未知應用」權限則先導向設定頁。
 */
object ApkInstaller {
    fun launch(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return false
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(install)
        }.onFailure {
            Toast.makeText(context, it.message ?: "Install failed", Toast.LENGTH_SHORT).show()
        }
        return true
    }
}
