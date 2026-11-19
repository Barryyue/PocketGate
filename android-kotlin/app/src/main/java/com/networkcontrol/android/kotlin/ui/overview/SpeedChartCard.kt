package com.networkcontrol.android.kotlin.ui.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.networkcontrol.android.kotlin.ui.common.formatBytes
import com.networkcontrol.android.kotlin.ui.theme.LocalAppVisualTokens

/**
 * 旗艦即時網速波形圖卡片：繪製最近一段時間的上下行平滑貝茲曲線與柔和漸層填充，
 * 自動適配 6 套視覺主題令牌並啟用等寬數字（Tabular Numerals）。
 */
@Composable
fun SpeedChartCard(
    title: String,
    downloadBytesPerSec: Long,
    uploadBytesPerSec: Long,
    downloadHistory: List<Long>,
    uploadHistory: List<Long>,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppVisualTokens.current
    val scheme = MaterialTheme.colorScheme
    val container = if (tokens.cardContainer != Color.Unspecified) {
        tokens.cardContainer
    } else {
        scheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val border = if (tokens.cardBorder != Color.Transparent) {
        BorderStroke(1.dp, tokens.cardBorder)
    } else {
        null
    }
    val downColor = scheme.primary
    val upColor = scheme.tertiary
    val gridColor = scheme.onSurfaceVariant.copy(alpha = if (tokens.isDarkCanvas) 0.14f else 0.16f)

    val peakBytes = maxOf(
        downloadHistory.maxOrNull() ?: 0L,
        uploadHistory.maxOrNull() ?: 0L,
        1024L,
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = scheme.onSurface,
        ),
        shape = RoundedCornerShape(tokens.cardCornerRadius),
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    "${formatBytes(peakBytes)}/s",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpeedLegendValue(
                    dotColor = downColor,
                    prefix = "↓",
                    value = "${formatBytes(downloadBytesPerSec)}/s",
                )
                SpeedLegendValue(
                    dotColor = upColor,
                    prefix = "↑",
                    value = "${formatBytes(uploadBytesPerSec)}/s",
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp),
            ) {
                val width = size.width
                val height = size.height
                if (width <= 0f || height <= 0f) return@Canvas

                // 兩條細虛線參考網格
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                listOf(0.25f, 0.65f).forEach { ratio ->
                    val y = height * ratio
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                }

                val maxVal = peakBytes.toFloat().coerceAtLeast(1024f)
                val topPad = 6.dp.toPx()
                val bottomPad = 4.dp.toPx()
                val usableHeight = (height - topPad - bottomPad).coerceAtLeast(1f)

                fun buildCurves(samples: List<Long>): Pair<Path, Path> {
                    val strokePath = Path()
                    val fillPath = Path()
                    if (samples.size < 2) return strokePath to fillPath
                    val stepX = width / (samples.size - 1).coerceAtLeast(1)
                    val points = samples.mapIndexed { idx, value ->
                        val ratio = (value.toFloat() / maxVal).coerceIn(0f, 1f)
                        Offset(
                            x = idx * stepX,
                            y = height - bottomPad - ratio * usableHeight,
                        )
                    }
                    strokePath.moveTo(points.first().x, points.first().y)
                    fillPath.moveTo(points.first().x, height)
                    fillPath.lineTo(points.first().x, points.first().y)
                    for (i in 0 until points.lastIndex) {
                        val p0 = points[i]
                        val p1 = points[i + 1]
                        val midX = (p0.x + p1.x) / 2f
                        strokePath.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                        fillPath.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                    }
                    fillPath.lineTo(points.last().x, height)
                    fillPath.close()
                    return strokePath to fillPath
                }

                // 先畫上行（較輕）再畫下行（主色突出）
                val (upStroke, upFill) = buildCurves(uploadHistory)
                drawPath(
                    path = upFill,
                    brush = Brush.verticalGradient(
                        colors = listOf(upColor.copy(alpha = 0.16f), Color.Transparent),
                        startY = topPad,
                        endY = height,
                    ),
                )
                drawPath(
                    path = upStroke,
                    color = upColor.copy(alpha = 0.85f),
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )

                val (downStroke, downFill) = buildCurves(downloadHistory)
                drawPath(
                    path = downFill,
                    brush = Brush.verticalGradient(
                        colors = listOf(downColor.copy(alpha = 0.28f), Color.Transparent),
                        startY = topPad,
                        endY = height,
                    ),
                )
                drawPath(
                    path = downStroke,
                    color = downColor,
                    style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

@Composable
private fun SpeedLegendValue(
    dotColor: Color,
    prefix: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$prefix $value",
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
        )
    }
}
