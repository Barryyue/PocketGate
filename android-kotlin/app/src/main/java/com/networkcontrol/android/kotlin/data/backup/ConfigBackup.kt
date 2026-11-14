package com.networkcontrol.android.kotlin.data.backup

import android.content.Context
import com.networkcontrol.android.kotlin.data.db.ConfigDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ConfigBackup(
    private val context: Context,
    private val database: ConfigDatabase,
) {
    suspend fun backupIfNeeded() = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "backups").apply { mkdirs() }
        val date = LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.BASIC_ISO_DATE)
        val target = File(directory, "config-$date.db")
        if (target.exists()) return@withContext

        val main = context.getDatabasePath(ConfigDatabase.NAME)
        if (!main.isFile) return@withContext

        runCatching {
            database.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL);")
        }
        val temp = File(directory, "config-$date.db.tmp")
        main.copyTo(temp, overwrite = true)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("無法建立配置備份。")
        }
        cleanup(directory)
    }

    private fun cleanup(directory: File) {
        val backups = directory.listFiles()
            ?.filter { it.name.matches(Regex("config-\\d{8}\\.db")) }
            ?.sortedByDescending { it.name }
            .orEmpty()
        backups.drop(7).forEach { it.delete() }
    }
}
