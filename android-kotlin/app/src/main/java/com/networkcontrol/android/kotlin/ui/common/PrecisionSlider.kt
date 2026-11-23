package com.networkcontrol.android.kotlin.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 精準數值調節滑塊：
 * - 滑塊本身用於粗調（不設過密刻度點，避免手指抬起時吸附偏移）；
 * - 兩端 `-` / `+` 按鈕支援輕點精調（單步）與長按連發，並附帶觸覺回饋；
 * - 點擊右上角數值膠囊可直接輸入精確數值。
 */
@Composable
fun PrecisionIntSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    step: Int = 1,
    formatValue: (Int) -> String = { it.toString() },
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = rememberUiStrings()
    val haptic = LocalHapticFeedback.current
    var showInputDialog by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)

    fun adjustBy(delta: Int) {
        val next = (latestValue + delta).coerceIn(valueRange.first, valueRange.last)
        if (next != latestValue) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            latestOnChange(next)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
                    .clickable {
                        inputText = value.toString()
                        showInputDialog = true
                    }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = formatValue(value),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RepeatableStepButton(
                symbol = "−",
                enabled = value > valueRange.first,
                onStep = { adjustBy(-step) },
            )
            Slider(
                value = value.toFloat(),
                onValueChange = { raw ->
                    val rounded = raw.roundToInt().coerceIn(valueRange.first, valueRange.last)
                    if (rounded != latestValue) {
                        latestOnChange(rounded)
                    }
                },
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                modifier = Modifier.weight(1f),
            )
            RepeatableStepButton(
                symbol = "+",
                enabled = value < valueRange.last,
                onStep = { adjustBy(step) },
            )
        }
    }

    if (showInputDialog) {
        val parsed = inputText.trim().toIntOrNull()
        val inRange = parsed != null && parsed in valueRange
        AlertDialog(
            onDismissRequest = { showInputDialog = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    singleLine = true,
                    isError = inputText.isNotBlank() && !inRange,
                    supportingText = {
                        Text("${valueRange.first} ~ ${valueRange.last}")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = inRange,
                    onClick = {
                        parsed?.let { onValueChange(it) }
                        showInputDialog = false
                    },
                ) {
                    Text(s[S.Save])
                }
            },
            dismissButton = {
                TextButton(onClick = { showInputDialog = false }) {
                    Text(s[S.Cancel])
                }
            },
        )
    }
}

/** 百分比浮點滑塊（0f..1f），內部以 0..100 整數精確步進，消除浮點誤差。 */
@Composable
fun PrecisionPercentSlider(
    label: String,
    value: Float,
    stepPercent: Int = 1,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val percent = (value * 100f).roundToInt().coerceIn(0, 100)
    PrecisionIntSlider(
        label = label,
        value = percent,
        valueRange = 0..100,
        step = stepPercent,
        formatValue = { "$it%" },
        onValueChange = { next -> onValueChange((next / 100f).coerceIn(0f, 1f)) },
        modifier = modifier,
    )
}

@Composable
private fun RepeatableStepButton(
    symbol: String,
    enabled: Boolean,
    onStep: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnStep by rememberUpdatedState(onStep)

    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(containerColor)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (!latestEnabled) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    latestOnStep()
                    val repeatJob = scope.launch {
                        delay(360L)
                        while (latestEnabled) {
                            latestOnStep()
                            delay(70L)
                        }
                    }
                    waitForUpOrCancellation()
                    repeatJob.cancel()
                }
            },
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}
