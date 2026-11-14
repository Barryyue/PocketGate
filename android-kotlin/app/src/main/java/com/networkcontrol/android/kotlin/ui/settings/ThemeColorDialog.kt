package com.networkcontrol.android.kotlin.ui.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings
import com.networkcontrol.android.kotlin.ui.rememberUiStrings

private data class ThemePreset(val label: S, val color: Color)

private val presets = listOf(
    ThemePreset(S.ThemePresetGreen, Color(0xFF2E7D32)),
    ThemePreset(S.ThemePresetBlue, Color(0xFF1565C0)),
    ThemePreset(S.ThemePresetIndigo, Color(0xFF3949AB)),
    ThemePreset(S.ThemePresetPurple, Color(0xFF6A1B9A)),
    ThemePreset(S.ThemePresetPink, Color(0xFFAD1457)),
    ThemePreset(S.ThemePresetRed, Color(0xFFC62828)),
    ThemePreset(S.ThemePresetOrange, Color(0xFFEF6C00)),
    ThemePreset(S.ThemePresetTeal, Color(0xFF00695C)),
    ThemePreset(S.ThemePresetBrown, Color(0xFF6D4C41)),
    ThemePreset(S.ThemePresetGray, Color(0xFF546E7A)),
)

@Composable
fun ThemeColorDialog(
    current: Color,
    onDismiss: () -> Unit,
    onSelect: (Color) -> Unit,
) {
    val s = rememberUiStrings()
    val initialHsv = remember {
        FloatArray(3).also { AndroidColor.colorToHSV(current.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    var selectedPreset by remember { mutableStateOf<Color?>(null) }

    val preview = hsvColor(hue, saturation, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.ThemeColor]) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(preview)
                            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    Text(
                        text = "#%06X".format(preview.toArgb() and 0xFFFFFF),
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                Text(s[S.PresetColors], style = MaterialTheme.typography.labelLarge)
                presets.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { preset ->
                            val selected = selectedPreset == preset.color
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(preset.color)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        selectedPreset = preset.color
                                        val hsv = FloatArray(3).also {
                                            AndroidColor.colorToHSV(preset.color.toArgb(), it)
                                        }
                                        hue = hsv[0]
                                        saturation = hsv[1]
                                        value = hsv[2]
                                    },
                            )
                        }
                    }
                }

                Text(s[S.CustomColor], style = MaterialTheme.typography.labelLarge)
                HueBar(hue) { newHue ->
                    selectedPreset = null
                    hue = newHue
                }
                SaturationValueBox(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                ) { newSaturation, newValue ->
                    selectedPreset = null
                    saturation = newSaturation
                    value = newValue
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSelect(preview)
                    onDismiss()
                },
            ) { Text(s[S.Apply]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val colors = listOf(
        Color.Red,
        Color.Yellow,
        Color.Green,
        Color.Cyan,
        Color.Blue,
        Color.Magenta,
        Color.Red,
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange((offset.x / size.width * 360f).coerceIn(0f, 359.9f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onChange((change.position.x / size.width * 360f).coerceIn(0f, 359.9f))
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(colors))
        val x = (hue / 360f) * size.width
        drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
    }
}

@Composable
private fun SaturationValueBox(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(hue) {
                detectTapGestures { offset ->
                    onChange(
                        (offset.x / size.width).coerceIn(0f, 1f),
                        (1f - offset.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    onChange(
                        (change.position.x / size.width).coerceIn(0f, 1f),
                        (1f - change.position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            },
    ) {
        drawRect(hsvColor(hue, 1f, 1f))
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawCircle(
            Color.White,
            radius = 7.dp.toPx(),
            center = Offset(saturation * size.width, (1f - value) * size.height),
            style = Stroke(3.dp.toPx()),
        )
    }
}

private fun hsvColor(hue: Float, saturation: Float, value: Float): Color =
    Color(
        AndroidColor.HSVToColor(
            floatArrayOf(
                hue.coerceIn(0f, 360f),
                saturation.coerceIn(0f, 1f),
                value.coerceIn(0f, 1f),
            ),
        ),
    )
