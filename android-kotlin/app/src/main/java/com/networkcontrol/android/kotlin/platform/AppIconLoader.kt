package com.networkcontrol.android.kotlin.platform

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

object AppIconLoader {
    private val cache = LruCache<String, Bitmap>(256)

    fun loadBitmap(context: Context, packageName: String): Bitmap? {
        cache.get(packageName)?.let { return it }
        val bitmap = runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawable.toBitmap(96, 96)
        }.getOrNull() ?: return null
        cache.put(packageName, bitmap)
        return bitmap
    }

    fun load(context: Context, packageName: String): ImageBitmap? =
        loadBitmap(context, packageName)?.asImageBitmap()
}
