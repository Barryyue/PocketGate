package com.networkcontrol.android.kotlin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppContainer
import com.networkcontrol.android.kotlin.core.model.ToolbarStyle
import com.networkcontrol.android.kotlin.ui.apps.AppsScreen
import com.networkcontrol.android.kotlin.ui.logs.LogsScreen
import com.networkcontrol.android.kotlin.ui.overview.OverviewScreen
import com.networkcontrol.android.kotlin.ui.prompt.PromptOverlay
import com.networkcontrol.android.kotlin.ui.rules.RulesScreen
import com.networkcontrol.android.kotlin.ui.settings.SettingsScreen
import com.networkcontrol.android.kotlin.ui.settings.SpeedIslandSettingsScreen
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private data class Tab(val label: S, val icon: ImageVector)

private val tabs = listOf(
    Tab(S.Overview, Icons.Filled.Home),
    Tab(S.Apps, Icons.AutoMirrored.Filled.List),
    Tab(S.Rules, Icons.Filled.Lock),
    Tab(S.Logs, Icons.Filled.Notifications),
    Tab(S.Settings, Icons.Filled.Settings),
)

@Composable
fun AppRoot(container: AppContainer) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var showAppListPermission by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var islandSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var blocklistsOpen by rememberSaveable { mutableStateOf(false) }
    val appListPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        container.appCatalog.invalidate()
        showAppListPermission = container.appCatalog.visibility().restricted
        if (showAppListPermission) {
            context.startActivity(container.backend.openAppSettings())
        }
    }

    LaunchedEffect(Unit) {
        container.awaitStarted()
        showAppListPermission = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.appCatalog.visibility().restricted
        }
        ready = true
    }

    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
            Text(
                s[S.Loading],
                modifier = Modifier.padding(top = 72.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val backend = container.backend
    val toolbarStyle by container.theme.toolbarStyle.collectAsStateWithLifecycle()
    val hazeState = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        if (blocklistsOpen) {
            Box(
                Modifier.fillMaxSize().widthIn(max = 760.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                com.networkcontrol.android.kotlin.ui.settings.BlocklistSettingsScreen(
                    backend = backend,
                    onBack = { blocklistsOpen = false },
                )
            }
        } else if (islandSettingsOpen) {
            Box(
                Modifier.fillMaxSize().widthIn(max = 760.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                SpeedIslandSettingsScreen(
                    backend = backend,
                    onBack = { islandSettingsOpen = false },
                )
            }
        } else {
            val floatingToolbar = toolbarStyle.isFloating
            val overlayToolbar = toolbarStyle != ToolbarStyle.ClassicSolid
            val contentBottom = when {
                !overlayToolbar -> 0.dp
                floatingToolbar -> 104.dp
                else -> 84.dp
            }
            Scaffold(
                bottomBar = {
                    if (!overlayToolbar) {
                        AppBottomBar(
                            style = toolbarStyle,
                            selected = selected,
                            onSelect = { selected = it },
                            hazeState = hazeState,
                        )
                    }
                },
            ) { padding ->
                CompositionLocalProvider(
                    LocalBottomContentPadding provides contentBottom,
                ) {
                    Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            Modifier
                                .widthIn(max = 760.dp)
                                .fillMaxSize()
                                .then(
                                    if (overlayToolbar) {
                                        Modifier.hazeSource(state = hazeState)
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            when (selected) {
                                0 -> OverviewScreen(backend)
                                1 -> AppsScreen(backend)
                                2 -> RulesScreen(backend)
                                3 -> LogsScreen(backend)
                                else -> SettingsScreen(
                                    backend = backend,
                                    onOpenIslandSettings = { islandSettingsOpen = true },
                                    onOpenBlocklists = { blocklistsOpen = true },
                                )
                            }
                        }
                    }
                }
            }
            if (overlayToolbar) {
                AppBottomBar(
                    style = toolbarStyle,
                    selected = selected,
                    onSelect = { selected = it },
                    hazeState = hazeState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        PromptOverlay(
            backend = backend,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 76.dp),
        )
    }

    if (showAppListPermission) {
        AlertDialog(
            onDismissRequest = { showAppListPermission = false },
            title = { Text(s[S.AppListPermissionTitle]) },
            text = {
                Text(
                    buildString {
                        append(s[S.AppListPermissionMessage])
                        val visibility = container.appCatalog.visibility()
                        if (visibility.installedCount > 0) {
                            append("\n\n")
                            append("${s[S.AppsCount]}：${visibility.installedCount}")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val visibility = container.appCatalog.visibility()
                        val oemPermission = visibility.oemPermissionName
                        if (oemPermission != null && !visibility.oemPermissionGranted) {
                            appListPermissionLauncher.launch(arrayOf(oemPermission))
                        } else {
                            context.startActivity(backend.openAppSettings())
                            showAppListPermission = false
                        }
                    },
                ) { Text(s[S.GoToSettings]) }
            },
            dismissButton = {
                TextButton(onClick = { showAppListPermission = false }) { Text(s[S.PromptLater]) }
            },
        )
    }
}

@Composable
private fun AppBottomBar(
    style: ToolbarStyle,
    selected: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val s = rememberUiStrings()
    val floating = style.isFloating
    val shape = if (floating) RoundedCornerShape(30.dp) else RectangleShape
    val hazeEdgeTreatment = if (floating) {
        BlurredEdgeTreatment(RoundedCornerShape(30.dp))
    } else {
        BlurredEdgeTreatment.Rectangle
    }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val glass = style == ToolbarStyle.FloatingGlass
    val transparent = style == ToolbarStyle.FloatingTransparent
    val frosted = style == ToolbarStyle.ClassicFrosted || style == ToolbarStyle.FloatingFrosted

    // 三種半透明材質都交給 Haze 處理，差別在模糊量、底色和高光：
    //  - 磨砂：26dp 模糊 + 68% 底色，霧面、沒有高光，後方內容只剩色塊。
    //  - 玻璃：18dp 模糊 + 8~18% 底色 + 鏡面高光/漸變邊框，背景顏色會透上來。
    //  - 透明：10dp 輕度模糊 + 44~52% 底色，內容看得見但不會和標籤打架。
    val hazeStyle = when {
        frosted -> HazeStyle(
            blurRadius = 26.dp,
            backgroundColor = scheme.surface,
            noiseFactor = 0.03f,
            tint = HazeTint(scheme.surface.copy(alpha = if (dark) 0.74f else 0.68f)),
            // Android 12 以下沒有 RenderEffect 模糊，Haze 會退回這層遮罩。
            fallbackTint = HazeTint(scheme.surface.copy(alpha = 0.96f)),
        )

        // 玻璃：底色壓低到 0.08 / 0.18，讓模糊後的光影和顏色真的透上來；
        // 高光只留在頂部一小段，底部加一層內陰影做出厚度，而不是整片泛白。
        glass -> HazeStyle(
            blurRadius = 18.dp,
            backgroundColor = scheme.surface,
            noiseFactor = 0.06f,
            tints = listOf(
                HazeTint(scheme.surface.copy(alpha = if (dark) 0.18f else 0.08f)),
                HazeTint(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (dark) 0.08f else 0.12f),
                        0.25f to Color.Transparent,
                        0.78f to Color.Transparent,
                        1f to Color.Black.copy(alpha = if (dark) 0.14f else 0.07f),
                    ),
                ),
            ),
            fallbackTint = HazeTint(scheme.surface.copy(alpha = 0.92f)),
        )

        // 透明：只做很淺的模糊，後方內容還在但不會和標籤打架；比玻璃更「實」、比磨砂更清楚。
        transparent -> HazeStyle(
            blurRadius = 10.dp,
            backgroundColor = scheme.surface,
            noiseFactor = 0.02f,
            tint = HazeTint(scheme.surface.copy(alpha = if (dark) 0.52f else 0.44f)),
            fallbackTint = HazeTint(scheme.surface.copy(alpha = 0.90f)),
        )

        else -> null
    }
    val borderBrush: Brush? = when (style) {
        ToolbarStyle.ClassicSolid -> null
        ToolbarStyle.ClassicFrosted,
        ToolbarStyle.FloatingFrosted,
        -> SolidColor(scheme.outlineVariant.copy(alpha = 0.55f))

        ToolbarStyle.FloatingSolid -> SolidColor(scheme.outlineVariant.copy(alpha = 0.35f))
        // 玻璃的邊框不是單一顏色：左上受光偏白、右下沉到陰影，才會像一片有厚度的玻璃。
        ToolbarStyle.FloatingGlass -> Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = if (dark) 0.30f else 0.90f),
                scheme.onSurface.copy(alpha = 0.12f),
                scheme.onSurface.copy(alpha = if (dark) 0.28f else 0.20f),
            ),
        )
        ToolbarStyle.FloatingTransparent -> SolidColor(scheme.onSurface.copy(alpha = 0.10f))
    }
    Box(
        modifier
            .fillMaxWidth()
            .then(if (floating) Modifier.padding(horizontal = 16.dp, vertical = 10.dp) else Modifier)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    when {
                        glass -> Modifier.shadow(
                            elevation = 20.dp,
                            shape = shape,
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.28f),
                            spotColor = Color.Black.copy(alpha = 0.38f),
                        )
                        transparent -> Modifier.shadow(8.dp, shape)
                        floating -> Modifier.shadow(10.dp, shape)
                        else -> Modifier
                    },
                )
                .then(
                    when {
                        // 讓 Haze 自己按圓角裁切模糊層；用 Modifier.clip 會把它的取樣邊界一起切掉，
                        // 圓角處反而出現方形的模糊斷面。
                        hazeStyle != null -> Modifier.hazeEffect(state = hazeState, style = hazeStyle) {
                            blurredEdgeTreatment = hazeEdgeTreatment
                        }
                        else -> Modifier.background(scheme.surface, shape)
                    },
                )
                .then(
                    if (borderBrush != null) {
                        Modifier.border(1.dp, borderBrush, shape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (glass) {
                // 內圈高光：玻璃邊緣的厚度感，比外框亮一級。
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(1.dp)
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (dark) 0.35f else 0.65f),
                                    Color.Transparent,
                                    Color.Transparent,
                                ),
                            ),
                            shape,
                        ),
                )
                // 頂部一道鏡面反光：兩端淡出，避免直角切斷圓角。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .padding(horizontal = 34.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = if (dark) 0.55f else 1f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                // 底部內陰影：玻璃有厚度，下緣不會和背景同亮度。
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.80f to Color.Transparent,
                                1f to Color.Black.copy(alpha = if (dark) 0.20f else 0.08f),
                            ),
                            shape,
                        ),
                )
            }
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                val indicator = if (glass) {
                    Color.White.copy(alpha = if (dark) 0.18f else 0.58f)
                } else if (transparent) {
                    scheme.surface.copy(alpha = if (dark) 0.28f else 0.55f)
                } else {
                    scheme.secondaryContainer
                }
                val colors = NavigationBarItemDefaults.colors(
                    indicatorColor = indicator,
                    selectedIconColor = if (glass || transparent) scheme.onSurface else scheme.onSecondaryContainer,
                    selectedTextColor = scheme.onSurface,
                    unselectedIconColor = scheme.onSurfaceVariant,
                    unselectedTextColor = scheme.onSurfaceVariant,
                )
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { onSelect(index) },
                        colors = colors,
                        icon = { Icon(tab.icon, contentDescription = s[tab.label]) },
                        label = { Text(s[tab.label]) },
                    )
                }
            }
        }
    }
}
