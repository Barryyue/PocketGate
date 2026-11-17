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
import androidx.compose.ui.draw.clip
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
    val shape = if (floating) RoundedCornerShape(28.dp) else RectangleShape
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val glass = style == ToolbarStyle.FloatingGlass
    val transparent = style == ToolbarStyle.FloatingTransparent
    val frosted = style == ToolbarStyle.ClassicFrosted || style == ToolbarStyle.FloatingFrosted

    // 三種半透明材質的區別（之前玻璃和透明都用 ultraThin，所以看起來完全一樣）：
    //  - 磨砂：高不透明度 + 中等模糊，霧面、沒有高光。
    //  - 玻璃：低不透明度 + 大模糊 + 白色高光 + 漸變邊框，背景顏色會透上來。
    //  - 透明：完全不模糊，只留一層很淡的底色，後方內容清晰可見。
    val hazeStyle = when {
        frosted -> HazeStyle(
            blurRadius = 26.dp,
            backgroundColor = scheme.surface,
            noiseFactor = 0.03f,
            tint = HazeTint(scheme.surface.copy(alpha = if (dark) 0.74f else 0.68f)),
            // Android 12 以下沒有 RenderEffect 模糊，Haze 會退回這層遮罩。
            fallbackTint = HazeTint(scheme.surface.copy(alpha = 0.96f)),
        )

        glass -> HazeStyle(
            blurRadius = 36.dp,
            backgroundColor = scheme.surface,
            noiseFactor = 0.12f,
            tints = listOf(
                HazeTint(scheme.surface.copy(alpha = if (dark) 0.34f else 0.24f)),
                HazeTint(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (dark) 0.14f else 0.28f),
                        0.42f to Color.Transparent,
                        1f to Color.White.copy(alpha = 0.06f),
                    ),
                ),
            ),
            fallbackTint = HazeTint(scheme.surface.copy(alpha = 0.88f)),
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
                Color.White.copy(alpha = if (dark) 0.34f else 0.80f),
                scheme.outlineVariant.copy(alpha = 0.28f),
                Color.White.copy(alpha = if (dark) 0.12f else 0.38f),
            ),
        )
        ToolbarStyle.FloatingTransparent -> SolidColor(scheme.onSurface.copy(alpha = 0.10f))
    }
    Box(
        modifier
            .fillMaxWidth()
            .then(if (floating) Modifier.padding(horizontal = 12.dp, vertical = 8.dp) else Modifier)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    when {
                        glass -> Modifier.shadow(
                            elevation = 18.dp,
                            shape = shape,
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.28f),
                            spotColor = Color.Black.copy(alpha = 0.38f),
                        )
                        floating -> Modifier.shadow(10.dp, shape)
                        else -> Modifier
                    },
                )
                // clip 必須在 hazeEffect 之前：模糊圖層要落在圓角裁切內部，否則會畫出一個方角矩形。
                .clip(shape)
                .then(
                    when {
                        hazeStyle != null -> Modifier.hazeEffect(state = hazeState, style = hazeStyle)
                        transparent -> Modifier.background(
                            scheme.surface.copy(alpha = if (dark) 0.26f else 0.20f),
                            shape,
                        )
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
                                    Color.White.copy(alpha = if (dark) 0.30f else 0.85f),
                                    Color.Transparent,
                                ),
                            ),
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
