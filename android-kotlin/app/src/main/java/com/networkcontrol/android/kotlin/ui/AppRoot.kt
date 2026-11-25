package com.networkcontrol.android.kotlin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppContainer
import com.networkcontrol.android.kotlin.BuildConfig
import com.networkcontrol.android.kotlin.platform.ApkInstaller
import com.networkcontrol.android.kotlin.core.model.ToolbarStyle
import com.networkcontrol.android.kotlin.ui.apps.AppsScreen
import com.networkcontrol.android.kotlin.ui.common.liquidGlassBackdrop
import com.networkcontrol.android.kotlin.ui.logs.LogsScreen
import com.networkcontrol.android.kotlin.ui.overview.OverviewScreen
import com.networkcontrol.android.kotlin.ui.prompt.PromptOverlay
import com.networkcontrol.android.kotlin.ui.rules.RulesScreen
import com.networkcontrol.android.kotlin.ui.settings.AppearanceSettingsScreen
import com.networkcontrol.android.kotlin.ui.settings.AutomationSettingsScreen
import com.networkcontrol.android.kotlin.ui.settings.BlocklistSettingsScreen
import com.networkcontrol.android.kotlin.ui.settings.DataBackupSettingsScreen
import com.networkcontrol.android.kotlin.ui.settings.ForwardingSettingsScreen
import com.networkcontrol.android.kotlin.ui.settings.PermissionsSettingsScreen
import com.networkcontrol.android.kotlin.ui.settings.ProfileScheduleSettingsScreen
import com.networkcontrol.android.kotlin.ui.settings.PromptSettingsScreen
import com.networkcontrol.android.kotlin.ui.settings.ProtectionSettingsScreen
import com.networkcontrol.android.kotlin.ui.settings.SettingsScreen
import com.networkcontrol.android.kotlin.ui.settings.SpeedIslandSettingsScreen
import com.networkcontrol.android.kotlin.ui.theme.LocalAppVisualTokens
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private data class Tab(val label: S, val icon: ImageVector)

private val tabs = listOf(
    Tab(S.Overview, Icons.Rounded.Home),
    Tab(S.Apps, Icons.AutoMirrored.Rounded.List),
    Tab(S.Rules, Icons.Rounded.Lock),
    Tab(S.Logs, Icons.Rounded.Notifications),
    Tab(S.Settings, Icons.Rounded.Settings),
)

@Composable
fun AppRoot(container: AppContainer) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var showAppListPermission by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var settingsSubScreen by rememberSaveable { mutableStateOf<String?>(null) }
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
        // 首次啟動交由總覽頁的 PermissionGuideDialog 統一引導，避免同時疊彈兩個權限視窗。
        showAppListPermission = if (container.backend.permissionGuideShown()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.appCatalog.visibility().restricted
            }
        } else {
            false
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
    val updateState by backend.updateState.collectAsStateWithLifecycle()
    var pendingInstallApk by remember { mutableStateOf<java.io.File?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        backend.checkUpdateOnEnter()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val pending = pendingInstallApk
        if (pending != null && context.packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = null
            ApkInstaller.launch(context, pending)
        }
    }
    val overviewListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val appsListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val rulesListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val logsListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val settingsListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val toolbarStyle by container.theme.toolbarStyle.collectAsStateWithLifecycle()
    val autoHideBottomBar by container.theme.autoHideBottomBar.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    var bottomBarVisible by remember { mutableStateOf(true) }
    LaunchedEffect(selected, autoHideBottomBar) {
        bottomBarVisible = true
    }
    val bottomBarScrollConnection = remember(autoHideBottomBar, density) {
        val hideThresholdPx = with(density) { 28.dp.toPx() }
        val showThresholdPx = with(density) { 14.dp.toPx() }
        var accum = 0f
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!autoHideBottomBar) return Offset.Zero
                val dy = consumed.y
                if (dy < 0f) {
                    if (accum > 0f) accum = 0f
                    accum += dy
                    if (accum <= -hideThresholdPx) {
                        bottomBarVisible = false
                        accum = 0f
                    }
                } else if (dy > 0f) {
                    if (accum < 0f) accum = 0f
                    accum += dy
                    if (accum >= showThresholdPx) {
                        bottomBarVisible = true
                        accum = 0f
                    }
                }
                return Offset.Zero
            }
        }
    }
    val showBottomBar = bottomBarVisible || !autoHideBottomBar
    val bottomBarHideProgress by animateFloatAsState(
        targetValue = if (showBottomBar) 0f else 1f,
        animationSpec = if (showBottomBar) {
            // 彈出顯示：較柔和剛度 + 輕微過衝回彈，呈現靈動上彈手感。
            spring(dampingRatio = 0.62f, stiffness = 240f)
        } else {
            // 收起隱藏：從容順滑下沉，不反彈晃眼。
            spring(dampingRatio = 0.86f, stiffness = 220f)
        },
        label = "bottom-bar-hide",
    )
    val visualTokens = LocalAppVisualTokens.current
    val canvasBackground = MaterialTheme.colorScheme.background
    val hazeState = rememberHazeState()
    Box(
        Modifier
            .fillMaxSize()
            .background(canvasBackground)
            .drawBehind {
                if (visualTokens.auroraPrimary != Color.Transparent) {
                    val primaryRadius = size.maxDimension * 0.65f
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(visualTokens.auroraPrimary, Color.Transparent),
                            center = Offset(size.width * 0.12f, size.height * 0.08f),
                            radius = primaryRadius,
                        ),
                    )
                }
                if (visualTokens.auroraSecondary != Color.Transparent) {
                    val secondaryRadius = size.maxDimension * 0.62f
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(visualTokens.auroraSecondary, Color.Transparent),
                            center = Offset(size.width * 0.90f, size.height * 0.86f),
                            radius = secondaryRadius,
                        ),
                    )
                }
            },
    ) {
        androidx.activity.compose.BackHandler(enabled = settingsSubScreen != null) {
            settingsSubScreen = null
        }
        if (settingsSubScreen != null) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                    when (settingsSubScreen) {
                        "blocklists" -> BlocklistSettingsScreen(
                            backend = backend,
                            onBack = { settingsSubScreen = null },
                        )
                        "island" -> SpeedIslandSettingsScreen(
                            backend = backend,
                            onBack = { settingsSubScreen = null },
                        )
                        "prompts" -> PromptSettingsScreen(
                            backend = backend,
                            onBack = { settingsSubScreen = null },
                        )
                        "forwarding" -> ForwardingSettingsScreen(
                            backend = backend,
                            onBack = { settingsSubScreen = null },
                        )
                        "profiles" -> ProfileScheduleSettingsScreen(
                            backend = backend,
                            onBack = { settingsSubScreen = null },
                        )
                        "automation" -> AutomationSettingsScreen(
                            backend = backend,
                            onBack = { settingsSubScreen = null },
                        )
                        "appearance" -> AppearanceSettingsScreen(
                            backend = backend,
                            onBack = { settingsSubScreen = null },
                        )
                        "protection" -> ProtectionSettingsScreen(
                            backend = backend,
                            onBack = { settingsSubScreen = null },
                        )
                        "permissions" -> PermissionsSettingsScreen(
                            backend = backend,
                            onBack = { settingsSubScreen = null },
                        )
                        "data" -> DataBackupSettingsScreen(
                            backend = backend,
                            onBack = { settingsSubScreen = null },
                        )
                        else -> LaunchedEffect(Unit) { settingsSubScreen = null }
                    }
                }
            }
        } else {
            val floatingToolbar = toolbarStyle.isFloating
            val useHazeSource = toolbarStyle != ToolbarStyle.ClassicSolid
            val barClearance = if (floatingToolbar) 104.dp else 84.dp
            val bottomBarHideModifier = Modifier.graphicsLayer {
                val scale = (1f - bottomBarHideProgress * 0.08f).coerceIn(0.88f, 1.04f)
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleX = scale
                scaleY = scale
                translationY = bottomBarHideProgress * (size.height + 56.dp.toPx())
                alpha = (1f - bottomBarHideProgress * 0.95f).coerceIn(0f, 1f)
            }
            // 所有導覽列樣式統一採浮層疊加，避免 Scaffold(bottomBar) 預留底部空白佔位；
            // 內容區延伸至螢幕最底端，導覽列下滑隱藏後列表即刻填滿釋放區域。
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) { padding ->
                val navBottom = padding.calculateBottomPadding()
                CompositionLocalProvider(
                    LocalBottomContentPadding provides (barClearance + navBottom),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(top = padding.calculateTopPadding())
                            .nestedScroll(bottomBarScrollConnection),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            Modifier
                                .widthIn(max = 760.dp)
                                .fillMaxSize()
                                .then(
                                    if (useHazeSource) {
                                        Modifier.hazeSource(state = hazeState)
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            AnimatedContent(
                                targetState = selected,
                                transitionSpec = {
                                    val dir = if (targetState > initialState) 1 else -1
                                    val enter = slideInHorizontally(
                                        animationSpec = spring(dampingRatio = 0.80f, stiffness = 420f),
                                        initialOffsetX = { (it / 14) * dir },
                                    ) + fadeIn(tween(180)) + scaleIn(
                                        initialScale = 0.98f,
                                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
                                    )
                                    val exit = slideOutHorizontally(
                                        animationSpec = tween(130),
                                        targetOffsetX = { -(it / 20) * dir },
                                    ) + fadeOut(tween(110))
                                    enter.togetherWith(exit)
                                },
                                label = "tab-transition",
                            ) { tab ->
                                when (tab) {
                                    0 -> OverviewScreen(
                                        backend = backend,
                                        listState = overviewListState,
                                        onOpenForwardingSettings = { settingsSubScreen = "forwarding" },
                                        onOpenBlocklists = { settingsSubScreen = "blocklists" },
                                        onOpenAutomation = { settingsSubScreen = "automation" },
                                        onOpenRules = { selected = 2 },
                                        onOpenLogs = { selected = 3 },
                                    )
                                    1 -> AppsScreen(backend, listState = appsListState)
                                    2 -> RulesScreen(backend, listState = rulesListState)
                                    3 -> LogsScreen(backend, listState = logsListState)
                                    else -> SettingsScreen(
                                        backend = backend,
                                        listState = settingsListState,
                                        onOpenIslandSettings = { settingsSubScreen = "island" },
                                        onOpenBlocklists = { settingsSubScreen = "blocklists" },
                                        onOpenPromptSettings = { settingsSubScreen = "prompts" },
                                        onOpenForwardingSettings = { settingsSubScreen = "forwarding" },
                                        onOpenProfileSettings = { settingsSubScreen = "profiles" },
                                        onOpenAutomation = { settingsSubScreen = "automation" },
                                        onOpenAppearanceSettings = { settingsSubScreen = "appearance" },
                                        onOpenProtectionSettings = { settingsSubScreen = "protection" },
                                        onOpenPermissionsSettings = { settingsSubScreen = "permissions" },
                                        onOpenDataBackupSettings = { settingsSubScreen = "data" },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            AppBottomBar(
                style = toolbarStyle,
                selected = selected,
                onSelect = { selected = it },
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(bottomBarHideModifier),
            )
        }
        val promptBottomPad = when {
            settingsSubScreen != null -> 16.dp
            toolbarStyle.isFloating -> 96.dp
            else -> 76.dp
        }
        PromptOverlay(
            backend = backend,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = promptBottomPad),
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

    val availableInfo = updateState.info
    if (updateState.showAvailablePrompt && availableInfo != null && availableInfo.hasUpdate) {
        val latest = availableInfo.latestVersion.orEmpty()
        val notes = availableInfo.releaseNotes
        AlertDialog(
            onDismissRequest = { backend.dismissUpdateAvailablePrompt() },
            title = { Text(s.format(S.UpdateAvailable, latest)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(s.format(S.UpdatePromptBody, BuildConfig.VERSION_NAME))
                    if (!notes.isNullOrBlank()) {
                        Text(
                            notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val url = availableInfo.downloadUrl
                        if (url != null && !url.endsWith(".apk", ignoreCase = true)) {
                            backend.dismissUpdateAvailablePrompt()
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        } else {
                            backend.startUpdateDownload(availableInfo) { err ->
                                android.widget.Toast.makeText(
                                    context,
                                    s.format(S.UpdateFailed, err),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) { Text(s[S.UpdateNow]) }
            },
            dismissButton = {
                TextButton(onClick = { backend.dismissUpdateAvailablePrompt() }) {
                    Text(s[S.PromptLater])
                }
            },
        )
    }

    val readyFile = updateState.downloadedFile
    if (updateState.showReadyPrompt && readyFile != null) {
        val latest = updateState.info?.latestVersion.orEmpty()
        AlertDialog(
            onDismissRequest = { backend.dismissUpdateReadyPrompt() },
            title = { Text(s.format(S.UpdateReadyTitle, latest)) },
            text = { Text(s.format(S.UpdateReadyBody, latest)) },
            confirmButton = {
                Button(
                    onClick = {
                        backend.dismissUpdateReadyPrompt()
                        if (!ApkInstaller.launch(context, readyFile)) {
                            pendingInstallApk = readyFile
                        }
                    },
                ) { Text(s[S.UpdateInstallNow]) }
            },
            dismissButton = {
                TextButton(onClick = { backend.dismissUpdateReadyPrompt() }) {
                    Text(s[S.UpdateInstallLater])
                }
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

        // 液態玻璃：
        // - Android 13+（支援 AGSL 折射）：降低模糊至 8dp、通透度 22%/26%，讓背後內容在滾動時呈現明顯的
        //   凸透鏡放大與邊緣液態折射、棱鏡色散及菲涅爾高光；
        // - Android 10–12（無 AGSL）：自動維持 24dp 模糊 + 42%/46% 乳白/深色毛玻璃以確保文字清晰。
        glass -> {
            val liquidSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
            HazeStyle(
                blurRadius = if (liquidSupported) 8.dp else 24.dp,
                backgroundColor = Color.Transparent,
                noiseFactor = if (liquidSupported) 0.015f else 0.03f,
                tints = listOf(
                    HazeTint(
                        scheme.surface.copy(
                            alpha = when {
                                liquidSupported && dark -> 0.26f
                                liquidSupported -> 0.22f
                                dark -> 0.46f
                                else -> 0.42f
                            },
                        ),
                    ),
                    HazeTint(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (dark) 0.16f else 0.34f),
                            0.26f to Color.Transparent,
                            0.74f to Color.Transparent,
                            1f to Color.Black.copy(alpha = if (dark) 0.20f else 0.08f),
                        ),
                    ),
                ),
                fallbackTint = HazeTint(scheme.surface.copy(alpha = 0.92f)),
            )
        }

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
                            elevation = 18.dp,
                            shape = shape,
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.28f),
                            spotColor = Color.Black.copy(alpha = 0.38f),
                        )
                        transparent -> Modifier.shadow(8.dp, shape, clip = false)
                        floating -> Modifier.shadow(10.dp, shape, clip = false)
                        else -> Modifier
                    },
                )
                // 在陰影之後裁切圓角：保留柔和外陰影，同時徹底消除 Haze 模糊與著色層在兩端露出的方形尾巴。
                .then(if (floating) Modifier.clip(shape) else Modifier)
                .then(
                    when {
                        hazeStyle != null && !glass -> Modifier.hazeEffect(state = hazeState, style = hazeStyle) {
                            blurredEdgeTreatment = hazeEdgeTreatment
                        }
                        hazeStyle == null -> Modifier.background(scheme.surface, shape)
                        else -> Modifier
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
            if (glass && hazeStyle != null) {
                // 獨立玻璃背景層：Haze 模糊 + Android 13+ AGSL 液態玻璃折射，不影響前景圖示與文字銳利度。
                Box(
                    Modifier
                        .matchParentSize()
                        .liquidGlassBackdrop(enabled = true, cornerRadius = 30.dp, dark = dark)
                        .hazeEffect(state = hazeState, style = hazeStyle) {
                            blurredEdgeTreatment = hazeEdgeTreatment
                        },
                )
            }
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
            val haptic = LocalHapticFeedback.current
            val pillColor = when {
                glass -> scheme.primary.copy(alpha = if (dark) 0.24f else 0.16f)
                transparent -> scheme.primary.copy(alpha = if (dark) 0.22f else 0.15f)
                else -> scheme.primary.copy(alpha = if (dark) 0.20f else 0.13f)
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                val slotWidth = maxWidth / tabs.size
                val pillOffset by animateDpAsState(
                    targetValue = slotWidth * selected,
                    animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
                    label = "nav-pill-offset",
                )
                // 帶彈性過衝的滑動膠囊指示器（同心圓角 24dp = 外框 30dp - 內距 6dp）
                Box(
                    modifier = Modifier
                        .offset(x = pillOffset)
                        .width(slotWidth)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(pillColor),
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    tabs.forEachIndexed { index, tab ->
                        val isSelected = selected == index
                        val iconScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.15f else 1.0f,
                            animationSpec = spring(dampingRatio = 0.45f, stiffness = 480f),
                            label = "nav-icon-scale-$index",
                        )
                        val iconLift by animateDpAsState(
                            targetValue = if (isSelected) (-1.5).dp else 0.dp,
                            animationSpec = spring(dampingRatio = 0.50f, stiffness = 480f),
                            label = "nav-icon-lift-$index",
                        )
                        val tint by animateColorAsState(
                            targetValue = if (isSelected) scheme.primary else scheme.onSurfaceVariant,
                            animationSpec = tween(220),
                            label = "nav-tint-$index",
                        )
                        val interaction = remember { MutableInteractionSource() }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .selectable(
                                    selected = isSelected,
                                    interactionSource = interaction,
                                    indication = null,
                                    role = androidx.compose.ui.semantics.Role.Tab,
                                    onClick = {
                                        if (!isSelected) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSelect(index)
                                        }
                                    },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier
                                    .size(22.dp)
                                    .offset(y = iconLift)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    },
                            )
                            Text(
                                text = s[tab.label],
                                color = tint,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
