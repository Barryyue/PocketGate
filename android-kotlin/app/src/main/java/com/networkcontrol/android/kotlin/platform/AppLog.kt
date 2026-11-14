package com.networkcontrol.android.kotlin.platform

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val TAG = "NetworkControlKT"
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var directory: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        directory = dir
        runCatching {
            val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        }
        i("app", "started")
    }

    fun d(tag: String, message: String) {
        Log.d(TAG, "$tag: $message")
    }

    fun i(tag: String, message: String) {
        Log.i(TAG, "$tag: $message")
        write("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(TAG, "$tag: $message")
        write("W", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(TAG, "$tag: $message", throwable)
        write("E", tag, message + (throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    private fun write(level: String, tag: String, message: String) {
        val dir = directory ?: return
        synchronized(lock) {
            runCatching {
                val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                val file = File(dir, "app-$day.log")
                val line = "${timeFormat.format(Date())} $level/$tag: $message\n"
                file.appendText(line)
            }
        }
    }
}
