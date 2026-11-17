package com.networkcontrol.android.kotlin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
            Scaffold(
                bottomBar = {
                    AppBottomBar(
                        style = toolbarStyle,
                        selected = selected,
                        onSelect = { selected = it },
                    )
                },
            ) { padding ->
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
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
) {
    val s = rememberUiStrings()
    val floating = style.isFloating
    val shape = if (floating) RoundedCornerShape(28.dp) else RectangleShape
    val scheme = MaterialTheme.colorScheme
    val brush: Brush = when (style) {
        ToolbarStyle.ClassicSolid,
        ToolbarStyle.FloatingSolid,
        -> androidx.compose.ui.graphics.SolidColor(scheme.surface)

        ToolbarStyle.ClassicFrosted,
        ToolbarStyle.FloatingFrosted,
        -> Brush.verticalGradient(
            listOf(
                scheme.surface.copy(alpha = 0.97f),
                scheme.surfaceVariant.copy(alpha = 0.90f),
            ),
        )

        ToolbarStyle.FloatingGlass -> Brush.verticalGradient(
            listOf(
                scheme.surface.copy(alpha = 0.72f),
                scheme.surfaceVariant.copy(alpha = 0.55f),
            ),
        )

        ToolbarStyle.FloatingTransparent -> Brush.verticalGradient(
            listOf(
                scheme.surface.copy(alpha = 0.38f),
                scheme.surface.copy(alpha = 0.18f),
            ),
        )
    }
    val borderColor = when (style) {
        ToolbarStyle.ClassicSolid -> null
        ToolbarStyle.ClassicFrosted,
        ToolbarStyle.FloatingFrosted,
        -> scheme.outlineVariant.copy(alpha = 0.60f)

        ToolbarStyle.FloatingSolid -> scheme.outlineVariant.copy(alpha = 0.35f)
        ToolbarStyle.FloatingGlass -> scheme.onSurface.copy(alpha = 0.14f)
        ToolbarStyle.FloatingTransparent -> scheme.onSurface.copy(alpha = 0.10f)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (floating) Modifier.padding(horizontal = 12.dp, vertical = 8.dp) else Modifier)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (floating) Modifier.shadow(10.dp, shape) else Modifier)
                .clip(shape)
                .background(brush, shape)
                .then(
                    if (borderColor != null) {
                        Modifier.border(1.dp, borderColor, shape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { onSelect(index) },
                        icon = { Icon(tab.icon, contentDescription = s[tab.label]) },
                        label = { Text(s[tab.label]) },
                    )
                }
            }
        }
    }
}
