package com.networkcontrol.android.kotlin.ui.settings

import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.island.SpeedIslandFormatter
import com.networkcontrol.android.kotlin.core.island.SpeedIslandLabels
import com.networkcontrol.android.kotlin.core.island.SpeedIslandStats
import com.networkcontrol.android.kotlin.core.model.SpeedIslandContent
import com.networkcontrol.android.kotlin.core.model.SpeedIslandFontWeight
import com.networkcontrol.android.kotlin.core.model.SpeedIslandMode
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.SectionCard
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlin.math.roundToInt

/**
 * 靈動島的獨立設定頁。所有外觀、內容、位置選項集中在這裡，方便後續擴展。
 */
@Composable
fun SpeedIslandSettingsScreen(backend: AppBackend, onBack: () -> Unit) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    val themeColor by backend.container.theme.color.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()

    var enabled by remember { mutableStateOf(backend.speedIslandEnabled()) }
    var nativeSpeedIcon by remember { mutableStateOf(backend.statusBarSpeedIcon()) }
    var mode by remember { mutableStateOf(backend.speedIslandMode()) }
    var content by remember { mutableStateOf(backend.speedIslandContent()) }
    var statusBarMode by remember { mutableStateOf(backend.speedIslandStatusBarMode()) }
    var vivoCompatible by remember { mutableStateOf(backend.vivoIslandCompatible()) }
    var offsetX by remember { mutableIntStateOf(backend.speedIslandOffsetXDp()) }
    var offsetY by remember { mutableIntStateOf(backend.speedIslandOffsetYDp()) }
    var opacity by remember { mutableFloatStateOf(backend.speedIslandOpacity()) }
    var textMode by remember { mutableStateOf(backend.speedIslandTextColorMode()) }
    var textColor by remember { mutableIntStateOf(backend.speedIslandTextColor()) }
    var dotColor by remember { mutableIntStateOf(backend.speedIslandDotColor()) }
    var showDot by remember { mutableStateOf(backend.speedIslandShowDot()) }
    var swapOrder by remember { mutableStateOf(backend.speedIslandSwapOrder()) }
    var fontScale by remember { mutableIntStateOf(backend.speedIslandFontScalePercent()) }
    var fontWeight by remember { mutableStateOf(backend.speedIslandFontWeight()) }
    var showTextColorDialog by remember { mutableStateOf(false) }
    var showDotColorDialog by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("‹ ${s[S.Back]}")
            }
            Text(
                s[S.IslandConfig],
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(s[S.IslandPreview]) {
                    IslandPreview(
                        content = content,
                        mode = mode,
                        opacity = opacity,
                        textMode = textMode,
                        customTextColor = textColor,
                        themeColor = themeColor,
                        dotColor = dotColor,
                        showDot = showDot,
                        swapOrder = swapOrder,
                        fontScale = fontScale,
                        fontWeight = fontWeight,
                        nightMode = dark,
                        labels = SpeedIslandLabels(
                            blocked = s[S.BlockedToday],
                            pending = s[S.PendingPrompts],
                            networkFallback = s[S.IslandContentNetwork],
                        ),
                    )
                    SwitchRow(
                        title = s[S.SpeedIsland],
                        subtitle = s[S.SpeedIslandHint],
                        checked = enabled,
                        onCheckedChange = { value ->
                            if (value && !Settings.canDrawOverlays(context)) {
                                Toast.makeText(context, s[S.OverlayDenied], Toast.LENGTH_SHORT).show()
                                runCatching { context.startActivity(backend.overlayPermissionIntent()) }
                            } else {
                                enabled = value
                                backend.setSpeedIslandEnabled(value)
                            }
                        },
                    )
                    SwitchRow(
                        title = s[S.StatusBarSpeedIcon],
                        subtitle = s[S.StatusBarSpeedIconHint],
                        checked = nativeSpeedIcon,
                        onCheckedChange = { value ->
                            nativeSpeedIcon = value
                            backend.setStatusBarSpeedIcon(value)
                        },
                    )
                }
            }

            item {
                SectionCard(s[S.IslandContent]) {
                    Text(
                        s[S.IslandContentHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SpeedIslandContent.entries.forEach { entry ->
                            FilterChip(
                                content == entry,
                                {
                                    content = entry
                                    backend.setSpeedIslandContent(entry)
                                },
                                { Text(contentLabel(s, entry)) },
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(s[S.IslandAppearance]) {
                    Text(s[S.IslandMode], style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            mode == SpeedIslandMode.Full,
                            {
                                mode = SpeedIslandMode.Full
                                backend.setSpeedIslandMode(SpeedIslandMode.Full)
                            },
                            { Text(s[S.IslandModeFull]) },
                        )
                        FilterChip(
                            mode == SpeedIslandMode.Cycle,
                            {
                                mode = SpeedIslandMode.Cycle
                                backend.setSpeedIslandMode(SpeedIslandMode.Cycle)
                            },
                            { Text(s[S.IslandModeCycle]) },
                        )
                        FilterChip(
                            mode == SpeedIslandMode.Mini,
                            {
                                mode = SpeedIslandMode.Mini
                                backend.setSpeedIslandMode(SpeedIslandMode.Mini)
                            },
                            { Text(s[S.IslandModeMini]) },
                        )
                    }
                    Text(
                        s.format(S.IslandOpacity, (opacity * 100).roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = opacity,
                        onValueChange = {
                            opacity = it
                            backend.setSpeedIslandOpacity(it)
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                    )
                    Text(
                        s.format(S.IslandFontSize, fontScale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = fontScale.toFloat(),
                        onValueChange = {
                            fontScale = it.roundToInt()
                            backend.setSpeedIslandFontScalePercent(fontScale)
                        },
                        valueRange = 80f..160f,
                        steps = 15,
                    )
                    Text(s[S.IslandFontWeight], style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpeedIslandFontWeight.entries.forEach { weight ->
                            FilterChip(
                                fontWeight == weight,
                                {
                                    fontWeight = weight
                                    backend.setSpeedIslandFontWeight(weight)
                                },
                                { Text(fontWeightLabel(s, weight)) },
                            )
                        }
                    }
                    Text(s[S.IslandTextColor], style = MaterialTheme.typography.bodyMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            textMode == "theme",
                            {
                                textMode = "theme"
                                backend.setSpeedIslandTextColorMode("theme")
                            },
                            { Text(s[S.FollowTheme]) },
                        )
                        FilterChip(
                            textMode == "custom",
                            {
                                textMode = "custom"
                                backend.setSpeedIslandTextColorMode("custom")
                            },
                            { Text(s[S.OverlayCustom]) },
                        )
                    }
                    if (textMode == "custom") {
                        OutlinedButton(onClick = { showTextColorDialog = true }) {
                            Box(
                                Modifier.size(16.dp).clip(CircleShape).background(Color(textColor)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(s[S.CustomColor])
                        }
                    }
                    SwitchRow(
                        title = s[S.IslandShowDot],
                        checked = showDot,
                        onCheckedChange = {
                            showDot = it
                            backend.setSpeedIslandShowDot(it)
                        },
                    )
                    if (showDot) {
                        OutlinedButton(onClick = { showDotColorDialog = true }) {
                            Box(
                                Modifier.size(16.dp).clip(CircleShape).background(Color(dotColor)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(s[S.IslandDotColor])
                        }
                    }
                    SwitchRow(
                        title = s[S.IslandSwapOrder],
                        checked = swapOrder,
                        onCheckedChange = {
                            swapOrder = it
                            backend.setSpeedIslandSwapOrder(it)
                        },
                    )
                }
            }

            item {
                SectionCard(s[S.IslandPosition]) {
                    SwitchRow(
                        title = s[S.StatusBarOverlay],
                        subtitle = s[S.StatusBarOverlayHint],
                        checked = statusBarMode,
                        onCheckedChange = {
                            statusBarMode = it
                            backend.setSpeedIslandStatusBarMode(it)
                            textMode = backend.speedIslandTextColorMode()
                        },
                    )
                    SwitchRow(
                        title = s[S.VivoIslandCompatible],
                        subtitle = s[S.VivoIslandHint],
                        checked = vivoCompatible,
                        onCheckedChange = {
                            vivoCompatible = it
                            backend.setVivoIslandCompatible(it)
                        },
                    )
                    Text(
                        s.format(S.IslandHorizontalOffset, offsetX),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = offsetX.toFloat(),
                        onValueChange = {
                            offsetX = it.roundToInt()
                            backend.setSpeedIslandOffsetXDp(offsetX)
                        },
                        valueRange = -60f..60f,
                        steps = 119,
                    )
                    Text(
                        s.format(S.IslandVerticalOffset, offsetY),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = offsetY.toFloat(),
                        onValueChange = {
                            offsetY = it.roundToInt()
                            backend.setSpeedIslandOffsetYDp(offsetY)
                        },
                        valueRange = -60f..60f,
                        steps = 119,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s[S.IslandOffsetHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = offsetX != 0 || offsetY != 0,
                            onClick = {
                                offsetX = 0
                                offsetY = 0
                                backend.setSpeedIslandOffsetXDp(0)
                                backend.setSpeedIslandOffsetYDp(0)
                            },
                        ) { Text(s[S.Reset]) }
                    }
                }
            }
        }
    }

    if (showTextColorDialog) {
        ThemeColorDialog(
            current = Color(textColor),
            onDismiss = { showTextColorDialog = false },
            onSelect = {
                textColor = it.toArgb()
                backend.setSpeedIslandTextColor(textColor)
                showTextColorDialog = false
            },
            title = s[S.IslandTextColor],
        )
    }

    if (showDotColorDialog) {
        ThemeColorDialog(
            current = Color(dotColor),
            onDismiss = { showDotColorDialog = false },
            onSelect = {
                dotColor = it.toArgb()
                backend.setSpeedIslandDotColor(dotColor)
                showDotColorDialog = false
            },
            title = s[S.IslandDotColor],
        )
    }
}

@Composable
private fun IslandPreview(
    content: SpeedIslandContent,
    mode: SpeedIslandMode,
    opacity: Float,
    textMode: String,
    customTextColor: Int,
    themeColor: Color,
    dotColor: Int,
    showDot: Boolean,
    swapOrder: Boolean,
    fontScale: Int,
    fontWeight: SpeedIslandFontWeight,
    nightMode: Boolean,
    labels: SpeedIslandLabels,
) {
    val stats = SpeedIslandStats(
        downloadBytesPerSecond = 1_258_291,
        uploadBytesPerSecond = 24_576,
        todayBytesIn = 1_932_735_283,
        todayBytesOut = 335_544_320,
        blockedToday = 12,
        pendingPrompts = 2,
        networkName = "Wi-Fi",
    )
    val text = SpeedIslandFormatter.format(
        content = content,
        mode = mode,
        stats = stats,
        swapOrder = swapOrder,
        showDownload = true,
        labels = labels,
    )
    val color = when (textMode) {
        "custom" -> Color(customTextColor)
        else -> themeColor
    }
    val compact = mode == SpeedIslandMode.Mini
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF335C81), Color(0xFF7FB3D5)),
                ),
            )
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(if (compact) 12.dp else 20.dp))
                .background(Color(0xFF101010).copy(alpha = opacity.coerceIn(0f, 1f)))
                .border(
                    1.dp,
                    if (opacity <= 0.001f) Color.Transparent else Color.White.copy(alpha = 0.25f),
                    RoundedCornerShape(if (compact) 12.dp else 20.dp),
                )
                .padding(
                    horizontal = if (compact) 8.dp else 14.dp,
                    vertical = if (compact) 4.dp else 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showDot) {
                Box(
                    Modifier
                        .padding(end = if (compact) 3.dp else 6.dp)
                        .size(if (compact) 5.dp else 8.dp)
                        .clip(CircleShape)
                        .background(Color(dotColor)),
                )
            }
            Text(
                text,
                color = color,
                fontSize = ((if (compact) 10f else 12f) * fontScale / 100f).sp,
                fontWeight = fontWeight.compose(),
                maxLines = 1,
            )
        }
    }
}

private fun SpeedIslandFontWeight.compose(): FontWeight = when (this) {
    SpeedIslandFontWeight.Normal -> FontWeight.Normal
    SpeedIslandFontWeight.Medium -> FontWeight.Medium
    SpeedIslandFontWeight.Bold -> FontWeight.Bold
}

private fun contentLabel(
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
    content: SpeedIslandContent,
): String = when (content) {
    SpeedIslandContent.Speed -> s[S.IslandContentSpeed]
    SpeedIslandContent.Traffic -> s[S.IslandContentTraffic]
    SpeedIslandContent.Blocked -> s[S.IslandContentBlocked]
    SpeedIslandContent.Pending -> s[S.IslandContentPending]
    SpeedIslandContent.Network -> s[S.IslandContentNetwork]
}

private fun fontWeightLabel(
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
    weight: SpeedIslandFontWeight,
): String = when (weight) {
    SpeedIslandFontWeight.Normal -> s[S.IslandFontWeightNormal]
    SpeedIslandFontWeight.Medium -> s[S.IslandFontWeightMedium]
    SpeedIslandFontWeight.Bold -> s[S.IslandFontWeightBold]
}
