package com.networkcontrol.android.kotlin.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat
import com.networkcontrol.android.kotlin.engine.traffic.SpeedSnapshot

/**
 * 原生狀態欄即時網速小圖示繪製器：
 * 將總網速（上下行合計）繪製為 24×24dp 單色 Alpha 遮罩位圖（上行大數字、下行單位），
 * 交給 Android SystemUI 作為通知 smallIcon——系統會自動隨任意前景 App 狀態欄底色變黑或變白，零額外權限。
 */
object StatusBarSpeedIcon {
    private const val SIZE_PX = 96

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textSize = 42f
    }

    /** 格式化為（數字字串, 單位字串）；10 以上採整數以騰出最大字號，確保在 16dp 通知圖示內清晰可讀。 */
    internal fun splitSpeed(bytesPerSecond: Long): Pair<String, String> {
        val bps = bytesPerSecond.coerceAtLeast(0L)
        return when {
            bps < 1024L -> bps.toString() to "B/s"
            bps < 10L * 1024L -> "%.1f".format(bps / 1024.0) to "KB/s"
            bps < 1024L * 1024L -> (bps / 1024L).toString() to "KB/s"
            bps < 10L * 1024L * 1024L -> "%.1f".format(bps / (1024.0 * 1024.0)) to "MB/s"
            bps < 1024L * 1024L * 1024L -> (bps / (1024L * 1024L)).toString() to "MB/s"
            else -> "%.1f".format(bps / (1024.0 * 1024.0 * 1024.0)) to "GB/s"
        }
    }

    fun render(speed: SpeedSnapshot): IconCompat {
        val total = speed.downloadBytesPerSecond + speed.uploadBytesPerSecond
        val (num, unit) = splitSpeed(total)
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 撐滿 96×96 畫布上下半區（減少留白浪費），確保 SystemUI 縮放到 ~16dp 時數字與單位依然清晰。
        var numSize = when {
            num.length >= 4 -> 50f
            num.length == 3 -> 54f
            else -> 60f
        }
        numberPaint.textSize = numSize
        val maxWidth = SIZE_PX - 2f
        val measuredNum = numberPaint.measureText(num)
        if (measuredNum > maxWidth) {
            numSize *= maxWidth / measuredNum
            numberPaint.textSize = numSize
        }

        var unitSize = 42f
        unitPaint.textSize = unitSize
        val measuredUnit = unitPaint.measureText(unit)
        if (measuredUnit > maxWidth) {
            unitSize *= maxWidth / measuredUnit
            unitPaint.textSize = unitSize
        }

        val cx = SIZE_PX / 2f
        canvas.drawText(num, cx, 49f, numberPaint)
        canvas.drawText(unit, cx, 92f, unitPaint)
        return IconCompat.createWithBitmap(bitmap)
    }
}
