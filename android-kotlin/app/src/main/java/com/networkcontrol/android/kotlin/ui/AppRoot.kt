package com.networkcontrol.android.kotlin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.networkcontrol.android.kotlin.AppContainer
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
    Box(Modifier.fillMaxSize()) {
        if (islandSettingsOpen) {
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
                    NavigationBar {
                        tabs.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selected == index,
                                onClick = { selected = index },
                                icon = { Icon(tab.icon, contentDescription = s[tab.label]) },
                                label = { Text(s[tab.label]) },
                            )
                        }
                    }
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
