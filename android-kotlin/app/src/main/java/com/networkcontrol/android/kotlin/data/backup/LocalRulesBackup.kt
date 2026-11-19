package com.networkcontrol.android.kotlin.data.backup

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 將規則快照同步留檔至公共下載目錄（`Download/PocketGate/`，Android 10+ 無需儲存權限），
 * 讓使用者解除安裝或重裝後可一鍵就近還原，不必每次手動尋找匯出檔。
 */
object LocalRulesBackup {
    private const val FILE_NAME = "pocketgate-rules-backup.json"
    private const val RELATIVE_DIR = "PocketGate"

    fun saveToDownloads(context: Context, json: String): Result<String> = runCatching {
        // 先寫一份到應用私有備份檔，確保立即可讀。
        val privateFile = File(context.filesDir, "backups/$FILE_NAME")
        privateFile.parentFile?.mkdirs()
        privateFile.writeText(json, Charsets.UTF_8)

        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$RELATIVE_DIR"
        val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val existingUri = resolver.query(
            queryUri,
            arrayOf(MediaStore.Downloads._ID),
            selection,
            arrayOf(FILE_NAME, "$relativePath%"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                android.content.ContentUris.withAppendedId(queryUri, id)
            } else {
                null
            }
        }

        val targetUri = existingUri ?: resolver.insert(
            queryUri,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            },
        ) ?: error("Unable to create MediaStore entry")

        resolver.openOutputStream(targetUri, "wt")?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        } ?: error("Unable to open Downloads output stream")

        "$relativePath/$FILE_NAME"
    }

    fun readLatestFromDownloads(context: Context): Result<String> = runCatching {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$RELATIVE_DIR"
        val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val uri = resolver.query(
            queryUri,
            arrayOf(MediaStore.Downloads._ID),
            selection,
            arrayOf(FILE_NAME, "$relativePath%"),
            "${MediaStore.Downloads.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                android.content.ContentUris.withAppendedId(queryUri, cursor.getLong(0))
            } else {
                null
            }
        }
        if (uri != null) {
            return@runCatching resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Unable to read Downloads backup")
        }
        val fallback = File(context.filesDir, "backups/$FILE_NAME")
        if (fallback.exists()) {
            return@runCatching fallback.readText(Charsets.UTF_8)
        }
        error("No local backup found in Download/$RELATIVE_DIR/$FILE_NAME")
    }
}
