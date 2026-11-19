package com.networkcontrol.android.kotlin.ui.settings

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.BuildConfig
import com.networkcontrol.android.kotlin.ForwardingUpstream
import com.networkcontrol.android.kotlin.core.focus.FocusSchedule
import com.networkcontrol.android.kotlin.core.model.AppThemeStyle
import com.networkcontrol.android.kotlin.core.model.AutoActivateCondition
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.InstalledApp
import com.networkcontrol.android.kotlin.core.model.NetworkContext
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.RuleImportMode
import com.networkcontrol.android.kotlin.core.model.RuleImportPreview
import com.networkcontrol.android.kotlin.core.model.SimInfo
import com.networkcontrol.android.kotlin.core.model.SpeedIslandContent
import com.networkcontrol.android.kotlin.core.model.SpeedIslandMode
import com.networkcontrol.android.kotlin.core.model.ToolbarStyle
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.release.ReleaseNote
import com.networkcontrol.android.kotlin.core.release.ReleaseNotes
import com.networkcontrol.android.kotlin.core.update.UpdateChecker
import com.networkcontrol.android.kotlin.core.update.UpdateInfo
import com.networkcontrol.android.kotlin.core.update.UpdateSource
import com.networkcontrol.android.kotlin.engine.apps.AppCatalog
import com.networkcontrol.android.kotlin.engine.dns.DohProviders
import com.networkcontrol.android.kotlin.engine.vpn.VpnRoutingPlanner
import com.networkcontrol.android.kotlin.ui.LocalBottomContentPadding
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings
import com.networkcontrol.android.kotlin.ui.common.ScreenHeader
import com.networkcontrol.android.kotlin.ui.common.SectionCard
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    backend: AppBackend,
    onOpenIslandSettings: () -> Unit = {},
    onOpenBlocklists: () -> Unit = {},
    onOpenPromptSettings: () -> Unit = {},
    onOpenForwardingSettings: () -> Unit = {},
    onOpenProfileSettings: () -> Unit = {},
) {
    val guard = LocalSensitiveAction.current
    val s = rememberUiStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by backend.settings.collectAsStateWithLifecycle()
    val runtime by backend.runtime.collectAsStateWithLifecycle()
    val profiles by backend.profiles.collectAsStateWithLifecycle()
    val plan by backend.plan.collectAsStateWithLifecycle()
    val ruleSet by backend.container.rules.ruleSet.collectAsStateWithLifecycle()
    val network by backend.network.collectAsStateWithLifecycle()
    val themeColor by backend.container.theme.color.collectAsStateWithLifecycle()
    val themeStyle by backend.container.theme.themeStyle.collectAsStateWithLifecycle()
    var toolbarStyle by remember { mutableStateOf(backend.toolbarStyle()) }
    val focusSchedules by backend.focusSchedules.collectAsStateWithLifecycle()
    val blocklistState by backend.blocklistState.collectAsStateWithLifecycle()
    val blocklistMatcher by backend.blocklistMatcher.collectAsStateWithLifecycle()
    val forwardingActive by backend.forwardingActive.collectAsStateWithLifecycle()
    val forwardingAvailable = remember { backend.forwardingAvailable() }

    var resumeKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumeKey++
        backend.refreshSpeedIsland()
    }
    val canDrawOverlays = remember(resumeKey) { backend.canDrawOverlays() }
    val hasTrafficAccess = remember(resumeKey) { backend.hasTrafficAccess() }
    val promptMode = remember(resumeKey) { backend.promptPresentationMode() }
    var speedIsland by remember(resumeKey) { mutableStateOf(backend.speedIslandEnabled()) }
    val islandOpacity = remember(resumeKey) { backend.speedIslandOpacity() }
    val islandMode = remember(resumeKey) { backend.speedIslandMode() }
    val islandContent = remember(resumeKey) { backend.speedIslandContent() }

    var blockOnLock by remember { mutableStateOf(backend.blockOnLockScreen()) }
    var lockWhitelist by remember { mutableStateOf(backend.lockScreenWhitelist()) }
    var showLockWhitelistDialog by remember { mutableStateOf(false) }
    var appLock by remember { mutableStateOf(backend.isAppLockEnabled()) }
    var offlineMode by remember { mutableStateOf(backend.offlineMode()) }
    var notificationsEnabled by remember(resumeKey) {
        mutableStateOf(backend.container.preferences.notificationsEnabled)
    }
    var autoHideBottomBar by remember(resumeKey) {
        mutableStateOf(backend.autoHideBottomBar())
    }
    var cloneIndependent by remember(resumeKey) {
        mutableStateOf(!backend.cloneAppsInheritPrimary())
    }
    var showWebDavDialog by remember { mutableStateOf(false) }
    var forwardingEnabled by remember(resumeKey) { mutableStateOf(backend.forwardingEnabled()) }
    val forwardingUpstream = remember(resumeKey) { backend.forwardingUpstream() }
    val viaProxy = forwardingUpstream == ForwardingUpstream.SOCKS5
    val socks5Host = remember(resumeKey) { backend.socks5Host() }
    val socks5Port = remember(resumeKey) { backend.socks5Port() }
    val exitDns = remember(resumeKey) { backend.exitDnsProvider() }
    val directPackages = remember(resumeKey) { backend.forwardingDirectPackages() }

    var notificationTitle by remember { mutableStateOf(backend.protectionNotificationTitle()) }
    var showNotificationTitleDialog by remember { mutableStateOf(false) }
    var showTrustedPublishersDialog by remember { mutableStateOf(false) }
    var showThemeColorDialog by remember { mutableStateOf(false) }

    val updateState by backend.updateState.collectAsStateWithLifecycle()
    var pendingInstallApk by remember { mutableStateOf<java.io.File?>(null) }
    var showReleaseNotes by remember { mutableStateOf(false) }
    var showReleaseHistory by remember { mutableStateOf(false) }

    fun launchApkInstaller(apkFile: java.io.File) {
        if (!com.networkcontrol.android.kotlin.platform.ApkInstaller.launch(context, apkFile)) {
            pendingInstallApk = apkFile
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val pending = pendingInstallApk
        if (pending != null && context.packageManager.canRequestPackageInstalls()) {
            launchApkInstaller(pending)
        }
    }

    var importJson by remember { mutableStateOf<String?>(null) }
    var previewMerge by remember { mutableStateOf<RuleImportPreview?>(null) }
    var previewReplace by remember { mutableStateOf<RuleImportPreview?>(null) }
    var importMode by remember { mutableStateOf(RuleImportMode.Merge) }
    var showImportDialog by remember { mutableStateOf(false) }

    // 已安裝應用列表需要 PackageManager：在背景載入，不在主執行緒上卡住界面。
    val pickerApps by produceState<List<InstalledApp>?>(
        initialValue = null,
        showLockWhitelistDialog,
    ) {
        if (showLockWhitelistDialog) {
            value = withContext(Dispatchers.IO) { backend.installedApps() }
        }
    }
    val installedCount by produceState(initialValue = -1) {
        value = withContext(Dispatchers.IO) { backend.container.appCatalog.getApps().size }
    }
    val appList by produceState<AppCatalog.AppListVisibility?>(
        initialValue = null,
        key1 = resumeKey,
    ) {
        value = withContext(Dispatchers.IO) { backend.appListVisibility() }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    LaunchedEffect(Unit) {
        backend.profileErrors.collect { message -> toast(message) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(backend.exportRules().toByteArray(Charsets.UTF_8))
                    }
                }
            }.onSuccess { toast(s[S.ExportDone]) }
                .onFailure { toast(s.format(S.ExportFailed, it.message.orEmpty())) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
            }.onSuccess { json ->
                if (json == null) {
                    toast(s.format(S.ImportFailed, "empty file"))
                    return@onSuccess
                }
                importJson = json
                previewMerge = runCatching { backend.previewImport(json, RuleImportMode.Merge) }.getOrNull()
                previewReplace = runCatching { backend.previewImport(json, RuleImportMode.Replace) }.getOrNull()
                if (previewMerge == null) {
                    toast(s.format(S.ImportFailed, "invalid file"))
                } else {
                    showImportDialog = true
                }
            }.onFailure { toast(s.format(S.ImportFailed, it.message.orEmpty())) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 20.dp + LocalBottomContentPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader(s[S.Settings], s[S.SettingsSubtitle]) }

        // 1. 外觀與顯示（語言、主題、主題色、工具欄樣式 Dropdown、靈動島）
        item {
            SectionCard(s[S.Appearance]) {
                DropdownSetting(
                    label = s[S.Language],
                    current = when (backend.container.preferences.languageTag) {
                        "zh-CN" -> s[S.ChineseSimplified]
                        "zh-TW" -> s[S.ChineseTraditional]
                        "en-US" -> s[S.English]
                        else -> s[S.SystemDefault]
                    },
                    options = listOf(
                        s[S.SystemDefault] to null,
                        s[S.ChineseSimplified] to "zh-CN",
                        s[S.ChineseTraditional] to "zh-TW",
                        s[S.English] to "en-US",
                    ),
                    onSelected = { backend.setLanguage(it) },
                )
                DropdownSetting(
                    label = s[S.Theme],
                    current = when (backend.container.preferences.themeMode) {
                        "light" -> s[S.ThemeLight]
                        "dark" -> s[S.ThemeDark]
                        else -> s[S.ThemeSystem]
                    },
                    options = listOf(
                        s[S.ThemeSystem] to "system",
                        s[S.ThemeLight] to "light",
                        s[S.ThemeDark] to "dark",
                    ),
                    onSelected = { backend.setTheme(it ?: "system") },
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s[S.ThemeStyle], style = MaterialTheme.typography.bodyMedium)
                    ThemeStyleStrip(
                        selected = themeStyle,
                        seedColor = themeColor,
                        s = s,
                        onSelect = { backend.setThemeStyle(it) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s[S.ThemeColor], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(themeColor)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { showThemeColorDialog = true }) {
                        Text(s[S.Edit])
                    }
                }
                DropdownSetting(
                    label = s[S.ToolbarStyle],
                    current = toolbarStyleLabel(s, toolbarStyle),
                    options = ToolbarStyle.entries.map { toolbarStyleLabel(s, it) to it.name },
                    onSelected = { name ->
                        val style = ToolbarStyle.entries.firstOrNull { it.name == name } ?: ToolbarStyle.ClassicSolid
                        toolbarStyle = style
                        backend.setToolbarStyle(style)
                    },
                )
                SwitchRow(
                    title = s[S.AutoHideBottomBar],
                    subtitle = s[S.AutoHideBottomBarHint],
                    checked = autoHideBottomBar,
                    onCheckedChange = { enabled ->
                        autoHideBottomBar = enabled
                        backend.setAutoHideBottomBar(enabled)
                    },
                )
                SwitchRow(
                    title = s[S.SpeedIsland],
                    subtitle = s[S.SpeedIslandHint],
                    checked = speedIsland,
                    onCheckedChange = { enabled ->
                        if (enabled && !canDrawOverlays) {
                            toast(s[S.OverlayDenied])
                        } else {
                            speedIsland = enabled
                            backend.setSpeedIslandEnabled(enabled)
                        }
                    },
                )
                if (speedIsland) {
                    val modeLabel = when (islandMode) {
                        SpeedIslandMode.Full -> s[S.IslandModeFull]
                        SpeedIslandMode.Cycle -> s[S.IslandModeCycle]
                        SpeedIslandMode.Mini -> s[S.IslandModeMini]
                    }
                    val contentLabel = when (islandContent) {
                        SpeedIslandContent.Speed -> s[S.IslandContentSpeed]
                        SpeedIslandContent.Traffic -> s[S.IslandContentTraffic]
                        SpeedIslandContent.Blocked -> s[S.IslandContentBlocked]
                        SpeedIslandContent.Pending -> s[S.IslandContentPending]
                        SpeedIslandContent.Network -> s[S.IslandContentNetwork]
                    }
                    SettingsNavRow(
                        title = s[S.IslandConfig],
                        subtitle = "$contentLabel · $modeLabel · ${(islandOpacity * 100).roundToInt()}%",
                        actionText = s[S.Edit],
                        onClick = onOpenIslandSettings,
                    )
                }
            }
        }

        // 2. 聯網詢問與通知（詢問二級頁入口、通知開關、通知標題彈框、系統通知設定）
        item {
            SectionCard("${s[S.PromptSettings]} · ${s[S.Notifications]}") {
                val presentationLabel = if (promptMode == "auto") {
                    s[S.PromptPresentationAuto]
                } else {
                    s[S.PromptPresentationNotification]
                }
                val defaultActionLabel = if (settings.promptDefaultAction == VerdictAction.Deny) {
                    s[S.Deny]
                } else {
                    s[S.Allow]
                }
                SettingsNavRow(
                    title = s[S.PromptSettings],
                    subtitle = "$presentationLabel · ${settings.promptTimeoutSeconds}s · $defaultActionLabel",
                    actionText = s[S.Edit],
                    onClick = onOpenPromptSettings,
                )
                SwitchRow(
                    title = s[S.NotificationsEnabled],
                    checked = notificationsEnabled,
                    onCheckedChange = { enabled ->
                        notificationsEnabled = enabled
                        backend.setNotificationsEnabled(enabled)
                    },
                )
                SettingsNavRow(
                    title = s[S.NotificationTitle],
                    subtitle = notificationTitle.ifBlank { s[S.ProtectionOn] },
                    actionText = s[S.Edit],
                    onClick = { showNotificationTitleDialog = true },
                )
                OutlinedButton(onClick = { context.startActivity(backend.openNotificationSettings()) }) {
                    Text(s[S.OpenNotificationSettings])
                }
            }
        }

        // 3. 防護、信任與網路（系統應用、可信發布者、鎖屏斷網、攔截列表、逐連線轉發模式）
        item {
            SectionCard(s[S.VpnSettings]) {
                SwitchRow(
                    title = s[S.AllowSystemApps],
                    subtitle = s[S.AllowSystemAppsHint],
                    checked = settings.allowSystemAppsByDefault,
                    onCheckedChange = { backend.setAllowSystemAppsByDefault(it) },
                )
                SettingsNavRow(
                    title = s[S.TrustedPublishers],
                    subtitle = if (settings.trustedSigners.isEmpty()) {
                        s[S.NoTrustedPublishers]
                    } else {
                        "${settings.trustedSigners.size}"
                    },
                    actionText = if (settings.trustedSigners.isEmpty()) null else s[S.Edit],
                    onClick = {
                        if (settings.trustedSigners.isNotEmpty()) {
                            showTrustedPublishersDialog = true
                        }
                    },
                )
                SwitchRow(
                    title = s[S.BlockOnLock],
                    subtitle = s[S.BlockOnLockHint],
                    checked = blockOnLock,
                    onCheckedChange = {
                        blockOnLock = it
                        backend.setBlockOnLockScreen(it)
                    },
                )
                if (blockOnLock) {
                    SettingsNavRow(
                        title = s[S.LockScreenWhitelist],
                        subtitle = s.format(S.LockScreenWhitelistCount, lockWhitelist.size),
                        actionText = s[S.Select],
                        onClick = { showLockWhitelistDialog = true },
                    )
                }
                SwitchRow(
                    title = s[S.CloneIndependent],
                    subtitle = s[S.CloneIndependentHint],
                    checked = cloneIndependent,
                    onCheckedChange = { independent ->
                        cloneIndependent = independent
                        backend.setCloneAppsInheritPrimary(!independent)
                    },
                )
                SettingsNavRow(
                    title = s[S.Blocklists],
                    subtitle = if (blocklistState.enabled) {
                        s.format(
                            S.BlocklistsActive,
                            NumberFormat.getIntegerInstance().format(blocklistMatcher.totalEntries),
                            blocklistState.lists.count { it.enabled && it.entries > 0 },
                        )
                    } else {
                        s[S.Off]
                    },
                    subtitleHighlighted = blocklistState.enabled,
                    actionText = s[S.Edit],
                    onClick = onOpenBlocklists,
                )
                SwitchRow(
                    title = s[S.ForwardingMode],
                    checked = forwardingEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !forwardingAvailable) {
                            toast(s[S.ForwardingUnavailable])
                        } else {
                            guard.run {
                                forwardingEnabled = enabled
                                backend.setForwardingEnabled(enabled)
                            }
                        }
                    },
                )
                val exitDnsLabel = when (exitDns) {
                    "system" -> s[S.ExitDnsSystem]
                    "custom" -> s[S.ExitDnsCustom]
                    else -> DohProviders.all.firstOrNull { it.id == exitDns }?.name ?: exitDns
                }
                val upstreamSummary = if (viaProxy) {
                    "${s[S.UpstreamSocks5]} ($socks5Host:$socks5Port)"
                } else {
                    "${s[S.UpstreamDirect]} ($exitDnsLabel)"
                }
                val forwardingStatusSummary = when {
                    !forwardingAvailable -> s[S.ForwardingUnavailable]
                    forwardingEnabled && forwardingActive -> "${s[S.ForwardingStatusActive]} · $upstreamSummary"
                    forwardingEnabled -> "${s[S.ForwardingStatusInactive]} · $upstreamSummary"
                    else -> "$upstreamSummary · ${s.format(S.DirectAppsCount, directPackages.size)}"
                }
                SettingsNavRow(
                    title = s[S.ForwardingUpstream],
                    subtitle = forwardingStatusSummary,
                    subtitleHighlighted = forwardingEnabled && forwardingActive,
                    actionText = s[S.Edit],
                    onClick = onOpenForwardingSettings,
                )
            }
        }

        // 4. 配置檔案與專注時間表
        item {
            SectionCard("${s[S.ProfileSettings]} · ${s[S.FocusSchedule]}") {
                val activeProfile = profiles.firstOrNull { it.id == runtime.activeProfileId } ?: profiles.firstOrNull()
                val profileSummary = if (activeProfile != null) {
                    "${activeProfile.name} (${activeProfile.mode.name}) · ${profiles.size}"
                } else {
                    "${profiles.size}"
                }
                SettingsNavRow(
                    title = s[S.ProfileSettings],
                    subtitle = profileSummary,
                    actionText = s[S.Edit],
                    onClick = onOpenProfileSettings,
                )
                val scheduleSummary = if (focusSchedules.isEmpty()) {
                    s[S.NoFocusSchedules]
                } else {
                    val activeCount = focusSchedules.count { it.enabled }
                    val first = focusSchedules.first()
                    "$activeCount / ${focusSchedules.size} · ${first.dayLabel(context)} ${first.timeLabel()}"
                }
                SettingsNavRow(
                    title = s[S.FocusSchedule],
                    subtitle = scheduleSummary,
                    actionText = s[S.Edit],
                    onClick = onOpenProfileSettings,
                )
            }
        }

        // 5. 安全與系統權限（應用鎖、6 項紅綠燈權限體檢、常駐 VPN 與電池最佳化）
        item {
            val vpnOk = remember(resumeKey) { backend.isVpnPrepared() }
            val notifOk = remember(resumeKey) { backend.notificationsAvailable() }
            val batteryOk = remember(resumeKey) { backend.isIgnoringBatteryOptimizations() }
            val appListRestricted = appList?.restricted == true
            val okCount = listOf(vpnOk, notifOk, hasTrafficAccess, !appListRestricted, canDrawOverlays, batteryOk).count { it }
            SectionCard("${s[S.PermissionHealth]} · $okCount / 6") {
                SwitchRow(
                    title = s[S.AppLock],
                    subtitle = s[S.AppLockHint],
                    checked = appLock,
                    onCheckedChange = { enabled ->
                        if (enabled && !backend.isDeviceSecure()) {
                            toast(s[S.AppLockNoCredential])
                        } else if (!enabled) {
                            guard.run {
                                appLock = false
                                backend.setAppLockEnabled(false)
                            }
                        } else {
                            appLock = true
                            backend.setAppLockEnabled(true)
                        }
                    },
                )
                SwitchRow(
                    title = s[S.OfflineMode],
                    subtitle = s[S.OfflineModeHint],
                    checked = offlineMode,
                    onCheckedChange = { enabled ->
                        offlineMode = enabled
                        backend.setOfflineMode(enabled)
                    },
                )
                PermissionRow(
                    title = s[S.PermVpn],
                    statusText = if (vpnOk) s[S.PermissionOk] else s[S.PermissionMissing],
                    ok = vpnOk,
                    actionText = if (vpnOk) null else s[S.GoToSettings],
                    onAction = { context.startActivity(backend.openAlwaysOnVpnSettings()) },
                )
                PermissionRow(
                    title = s[S.PermNotifications],
                    statusText = if (notifOk) s[S.PermissionOk] else s[S.PermissionMissing],
                    ok = notifOk,
                    actionText = if (notifOk) null else s[S.GoToSettings],
                    onAction = { context.startActivity(backend.openNotificationSettings()) },
                )
                PermissionRow(
                    title = s[S.PermUsage],
                    statusText = if (hasTrafficAccess) s[S.UsageAccessGranted] else s[S.UsageAccessDenied],
                    ok = hasTrafficAccess,
                    actionText = if (hasTrafficAccess) null else s[S.GrantAccess],
                    onAction = { context.startActivity(backend.trafficAccessIntent()) },
                )
                PermissionRow(
                    title = s[S.PermAppList],
                    statusText = if (appListRestricted) s[S.AppListPermissionRestricted] else s[S.AppListPermissionOk],
                    ok = !appListRestricted,
                    actionText = if (appListRestricted) s[S.GoToSettings] else null,
                    onAction = { context.startActivity(backend.openAppSettings()) },
                )
                PermissionRow(
                    title = s[S.PermOverlay],
                    statusText = if (canDrawOverlays) s[S.OverlayGranted] else s[S.OverlayDenied],
                    ok = canDrawOverlays,
                    actionText = if (canDrawOverlays) null else s[S.OverlayGrant],
                    onAction = { context.startActivity(backend.overlayPermissionIntent()) },
                )
                PermissionRow(
                    title = s[S.PermBattery],
                    statusText = if (batteryOk) s[S.PermissionOk] else s[S.PermissionMissing],
                    ok = batteryOk,
                    actionText = if (batteryOk) null else s[S.GoToSettings],
                    onAction = { context.startActivity(backend.openBatteryOptimizationSettings()) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { context.startActivity(backend.openAlwaysOnVpnSettings()) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(s[S.AlwaysOnVpn])
                    }
                    OutlinedButton(
                        onClick = { context.startActivity(backend.openBatteryOptimizationSettings()) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(s[S.BatteryOptimization])
                    }
                }
            }
        }

        // 6. 資料、診斷與關於（規則導入導出 + 診斷 + 檢查更新並排、版本與狀態摘要）
        item {
            SectionCard(s[S.About]) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { exportLauncher.launch("pocketgate-rules.json") }) {
                        Text(s[S.ExportRules])
                    }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                        Text(s[S.ImportRules])
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) { backend.saveLocalBackupToDownloads() }
                                res.onSuccess { path -> toast(s.format(S.LocalBackupSaved, path)) }
                                    .onFailure { toast(s.format(S.ExportFailed, it.message.orEmpty())) }
                            }
                        },
                    ) { Text(s[S.LocalBackupSave]) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) { backend.readLocalBackupFromDownloads() }
                                res.onSuccess { json ->
                                    runCatching {
                                        importJson = json
                                        previewMerge = backend.previewImport(json, RuleImportMode.Merge)
                                        previewReplace = backend.previewImport(json, RuleImportMode.Replace)
                                        importMode = RuleImportMode.Merge
                                        showImportDialog = true
                                    }.onFailure { toast(s.format(S.ImportFailed, it.message.orEmpty())) }
                                }.onFailure { toast(s.format(S.ImportFailed, it.message.orEmpty())) }
                            }
                        },
                    ) { Text(s[S.LocalBackupRestore]) }
                    OutlinedButton(onClick = { showWebDavDialog = true }) {
                        Text(s[S.WebDavSync])
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { backend.exportDiagnostics() } }
                                    .onSuccess { file ->
                                        val uri = backend.diagnosticsShareUri(file)
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/zip"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(send, s[S.Share]))
                                    }
                                    .onFailure { toast(s.format(S.ExportFailed, it.message.orEmpty())) }
                            }
                        },
                    ) { Text(s[S.ExportDiagnostics]) }
                    OutlinedButton(
                        onClick = {
                            if (offlineMode) {
                                toast(s[S.OfflineModeBlocked])
                                return@OutlinedButton
                            }
                            if (updateState.checking) return@OutlinedButton
                            backend.checkUpdateManually { info ->
                                scope.launch(Dispatchers.Main) {
                                    when {
                                        info.error != null -> toast(s.format(S.UpdateFailed, info.error))
                                        info.hasUpdate -> toast(s.format(S.UpdateAvailable, info.latestVersion))
                                        else -> toast(s[S.UpToDate])
                                    }
                                }
                            }
                        },
                        enabled = !updateState.checking,
                    ) {
                        Text(if (updateState.checking) s[S.Loading] else s[S.CheckUpdates])
                    }
                }
                SettingsNavRow(
                    title = "${s[S.Version]}：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    subtitle = "AndroidVpn · ${runtime.mode.name} / ${backend.protectionStatus().name} · " +
                        "${s[S.AppsCount]} ${if (installedCount < 0) "…" else installedCount} · " +
                        "${s[S.SkippedRules]} ${plan.skippedRules.size}",
                    actionText = s[S.ViewDetails],
                    onClick = {
                        showReleaseHistory = false
                        showReleaseNotes = true
                    },
                )
                if (backend.isWorkProfile()) {
                    Text(
                        "${s[S.WorkProfile]} ✓ · ${s[S.WorkProfileHint]}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                updateState.info?.takeIf { it.hasUpdate }?.let { info ->
                    val readyApk = updateState.downloadedFile
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s.format(S.UpdateAvailable, info.latestVersion.orEmpty()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = !updateState.downloading,
                                onClick = {
                                    if (offlineMode) {
                                        toast(s[S.OfflineModeBlocked])
                                        return@TextButton
                                    }
                                    if (readyApk != null) {
                                        launchApkInstaller(readyApk)
                                        return@TextButton
                                    }
                                    val url = info.downloadUrl ?: return@TextButton
                                    if (!url.endsWith(".apk", ignoreCase = true)) {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                        return@TextButton
                                    }
                                    backend.startUpdateDownload(info) { err ->
                                        scope.launch(Dispatchers.Main) {
                                            toast(s.format(S.UpdateFailed, err))
                                        }
                                    }
                                },
                            ) {
                                Text(
                                    when {
                                        updateState.downloading -> "${s[S.Download]} ${updateState.progress}%"
                                        readyApk != null -> s[S.UpdateInstallNow]
                                        else -> s[S.UpdateDownload]
                                    },
                                )
                            }
                        }
                        if (updateState.downloading) {
                            LinearProgressIndicator(
                                progress = { (updateState.progress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showThemeColorDialog) {
        ThemeColorDialog(
            current = themeColor,
            onDismiss = { showThemeColorDialog = false },
            onSelect = { backend.setThemeColor(it) },
        )
    }

    if (showNotificationTitleDialog) {
        NotificationTitleDialog(
            initialTitle = notificationTitle,
            onDismiss = { showNotificationTitleDialog = false },
            onReset = {
                notificationTitle = ""
                backend.setProtectionNotificationTitle("")
                showNotificationTitleDialog = false
                toast(s[S.Reset])
            },
            onSave = { newTitle ->
                notificationTitle = newTitle
                backend.setProtectionNotificationTitle(newTitle)
                showNotificationTitleDialog = false
                toast(s[S.Done])
            },
        )
    }

    if (showWebDavDialog) {
        WebDavBackupDialog(
            backend = backend,
            offlineMode = offlineMode,
            s = s,
            onToast = ::toast,
            onRestoreJson = { json ->
                runCatching {
                    importJson = json
                    previewMerge = backend.previewImport(json, RuleImportMode.Merge)
                    previewReplace = backend.previewImport(json, RuleImportMode.Replace)
                    importMode = RuleImportMode.Merge
                    showWebDavDialog = false
                    showImportDialog = true
                }.onFailure { toast(s.format(S.ImportFailed, it.message.orEmpty())) }
            },
            onDismiss = { showWebDavDialog = false },
        )
    }

    if (showTrustedPublishersDialog) {
        TrustedPublishersDialog(
            signers = settings.trustedSigners,
            onUntrust = { backend.untrustPublisher(it) },
            onClearAll = {
                backend.clearTrustedPublishers()
                showTrustedPublishersDialog = false
            },
            onDismiss = { showTrustedPublishersDialog = false },
        )
    }

    val directApps = pickerApps
    val allowedByRulePackages = remember(directApps, ruleSet.version, network) {
        directApps?.let { backend.allowedByRulePackages(it) } ?: emptySet()
    }
    if (showLockWhitelistDialog && directApps != null) {
        DirectAppsDialog(
            title = s[S.LockScreenWhitelist],
            apps = directApps,
            initial = lockWhitelist,
            allowedByRulePackages = allowedByRulePackages,
            onDismiss = { showLockWhitelistDialog = false },
            onSave = { packages ->
                lockWhitelist = packages
                backend.setLockScreenWhitelist(packages)
                showLockWhitelistDialog = false
            },
        )
    }

    if (showReleaseNotes) {
        ReleaseNotesDialog(
            currentVersion = BuildConfig.VERSION_NAME,
            history = showReleaseHistory,
            onToggleHistory = { showReleaseHistory = !showReleaseHistory },
            onDismiss = { showReleaseNotes = false },
        )
    }

    if (showImportDialog) {
        ImportDialog(
            preview = if (importMode == RuleImportMode.Merge) previewMerge else previewReplace,
            mode = importMode,
            onModeChange = { importMode = it },
            onDismiss = { showImportDialog = false },
            onApply = {
                val json = importJson
                if (json != null) {
                    scope.launch {
                        runCatching { backend.applyImport(json, importMode) }
                            .onSuccess { toast(s[S.ImportDone]) }
                            .onFailure { toast(s.format(S.ImportFailed, it.message.orEmpty())) }
                    }
                }
                showImportDialog = false
            },
        )
    }
}

@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String,
    actionText: String?,
    subtitleHighlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (subtitleHighlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (actionText != null) {
            TextButton(onClick = onClick) {
                Text(actionText)
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    statusText: String,
    ok: Boolean,
    actionText: String?,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (actionText != null) {
            OutlinedButton(onClick = onAction) {
                Text(actionText)
            }
        }
    }
}

@Composable
private fun WebDavBackupDialog(
    backend: AppBackend,
    offlineMode: Boolean,
    s: UiStrings,
    onToast: (String) -> Unit,
    onRestoreJson: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(backend.webdavUrl()) }
    var username by remember { mutableStateOf(backend.webdavUsername()) }
    var password by remember { mutableStateOf(backend.webdavPassword()) }
    var passphrase by remember { mutableStateOf(backend.webdavPassphrase()) }
    var busy by remember { mutableStateOf(false) }
    var lastSyncAt by remember { mutableStateOf(backend.webdavLastSyncAt()) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(s[S.WebDavSync]) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(s[S.WebDavUrl]) },
                    placeholder = { Text("https://dav.example.com/pocketgate/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(s[S.WebDavUsername]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(s[S.WebDavPassword]) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(s[S.WebDavPassphrase]) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (lastSyncAt > 0L) {
                    Text(
                        s.format(S.WebDavLastSync, com.networkcontrol.android.kotlin.ui.common.formatDateTime(lastSyncAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.Button(
                        enabled = !busy && url.isNotBlank() && passphrase.isNotBlank(),
                        onClick = {
                            if (offlineMode) {
                                onToast(s[S.OfflineModeBlocked])
                                return@Button
                            }
                            backend.saveWebdavConfig(url, username, password, passphrase)
                            busy = true
                            scope.launch {
                                val res = backend.uploadWebdavBackup()
                                busy = false
                                res.onSuccess {
                                    lastSyncAt = backend.webdavLastSyncAt()
                                    onToast(s[S.WebDavUploadOk])
                                }.onFailure { err ->
                                    onToast(s.format(S.ExportFailed, err.message.orEmpty()))
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(s[S.WebDavUpload]) }
                    OutlinedButton(
                        enabled = !busy && url.isNotBlank() && passphrase.isNotBlank(),
                        onClick = {
                            if (offlineMode) {
                                onToast(s[S.OfflineModeBlocked])
                                return@OutlinedButton
                            }
                            backend.saveWebdavConfig(url, username, password, passphrase)
                            busy = true
                            scope.launch {
                                val res = backend.downloadWebdavBackup()
                                busy = false
                                res.onSuccess { json ->
                                    onRestoreJson(json)
                                }.onFailure { err ->
                                    onToast(s.format(S.ImportFailed, err.message.orEmpty()))
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(s[S.WebDavRestore]) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    backend.saveWebdavConfig(url, username, password, passphrase)
                    onDismiss()
                },
            ) { Text(s[S.Close]) }
        },
    )
}

@Composable
private fun NotificationTitleDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onSave: (String) -> Unit,
) {
    val s = rememberUiStrings()
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.NotificationTitle]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(80) },
                    label = { Text(s[S.NotificationTitle]) },
                    placeholder = { Text(s[S.ProtectionOn]) },
                    supportingText = { Text(s[S.NotificationTitleHint]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onReset) {
                    Text(s[S.Reset])
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title) }) { Text(s[S.Save]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

@Composable
private fun TrustedPublishersDialog(
    signers: Set<String>,
    onUntrust: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = rememberUiStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.TrustedPublishers]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (signers.isEmpty()) {
                    Text(
                        s[S.NoTrustedPublishers],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    signers.forEach { signer ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${signer.take(24)}…",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onUntrust(signer) }) {
                                Text(s[S.Delete])
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Close]) }
        },
        dismissButton = {
            if (signers.isNotEmpty()) {
                TextButton(onClick = onClearAll) {
                    Text(s[S.ClearTrustedPublishers])
                }
            }
        },
    )
}

@Composable
internal fun ProfileRow(
    profile: Profile,
    active: Boolean,
    s: UiStrings,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                profile.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                "${profile.mode.name}${if (active) " · ${s[S.ActiveProfile]}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            profile.autoActivate?.let { auto ->
                val network = when (auto.kind) {
                    NetworkKind.Wifi -> s[S.Wifi]
                    NetworkKind.Cellular -> s[S.Cellular]
                    NetworkKind.Wired -> s[S.Wired]
                    else -> s[S.AnyNetwork]
                }
                Text(
                    "${s[S.AutoSwitch]}: $network${auto.ssids.firstOrNull()?.let { " · $it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (!active) {
            TextButton(onClick = onActivate) { Text(s[S.Open]) }
        }
        TextButton(onClick = onEdit) { Text(s[S.Edit]) }
        if (!active && profile.id != Profile.DEFAULT_ID) {
            TextButton(onClick = onDelete) { Text(s[S.Delete]) }
        }
    }
}

@Composable
internal fun ProfileDialog(
    profile: Profile?,
    currentNetwork: NetworkContext,
    loadSims: () -> List<SimInfo>,
    onDismiss: () -> Unit,
    onSave: (String, FilterMode, AutoActivateCondition?) -> Unit,
) {
    val s = rememberUiStrings()
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var mode by remember { mutableStateOf(profile?.mode ?: FilterMode.Ask) }
    var autoKind by remember { mutableStateOf(profile?.autoActivate?.kind) }
    var simSubscriptionId by remember { mutableStateOf(profile?.autoActivate?.subscriptionIds?.firstOrNull()) }
    var sims by remember { mutableStateOf(loadSims()) }
    var ssid by remember { mutableStateOf(profile?.autoActivate?.ssids?.firstOrNull().orEmpty()) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {}
    val phoneStateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { sims = loadSims() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) s[S.AddProfile] else s[S.Edit]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(s[S.ProfileName]) },
                    singleLine = true,
                )
                Text(s[S.ProfileMode], style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == FilterMode.Ask, { mode = FilterMode.Ask }, { Text(s[S.ModeAsk]) })
                    FilterChip(mode == FilterMode.Whitelist, { mode = FilterMode.Whitelist }, { Text(s[S.ModeWhitelist]) })
                    FilterChip(mode == FilterMode.Blacklist, { mode = FilterMode.Blacklist }, { Text(s[S.ModeBlacklist]) })
                }
                Text(s[S.AutoSwitch], style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(autoKind == null, { autoKind = null }, { Text(s[S.AnyNetwork]) })
                    FilterChip(
                        autoKind == NetworkKind.Wifi,
                        { autoKind = NetworkKind.Wifi },
                        { Text(s[S.Wifi]) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        autoKind == NetworkKind.Cellular,
                        { autoKind = NetworkKind.Cellular },
                        { Text(s[S.Cellular]) },
                    )
                    FilterChip(
                        autoKind == NetworkKind.Wired,
                        { autoKind = NetworkKind.Wired },
                        { Text(s[S.Wired]) },
                    )
                }
                Text(s[S.SimCard], style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        simSubscriptionId == null,
                        { simSubscriptionId = null },
                        { Text(s[S.AllSims]) },
                    )
                    sims.take(2).forEach { sim ->
                        FilterChip(
                            simSubscriptionId == sim.subscriptionId,
                            { simSubscriptionId = sim.subscriptionId },
                            { Text(sim.label) },
                        )
                    }
                }
                if (sims.isEmpty()) {
                    Text(
                        s[S.PhoneStateHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        phoneStateLauncher.launch(android.Manifest.permission.READ_PHONE_STATE)
                    }) { Text(s[S.GrantAccess]) }
                }
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text(s[S.Ssid]) },
                    singleLine = true,
                )
                if (currentNetwork.kind == NetworkKind.Wifi &&
                    currentNetwork.ssid == null
                ) {
                    Text(
                        s[S.SsidLocationHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            locationLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                    ) { Text(s[S.GrantAccess]) }
                }
                if (currentNetwork.kind != NetworkKind.None) {
                    TextButton(
                        onClick = {
                            autoKind = currentNetwork.kind
                            ssid = currentNetwork.ssid.orEmpty()
                        },
                    ) { Text(s[S.UseCurrentNetwork]) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        val auto = if (autoKind == null && ssid.isBlank() && simSubscriptionId == null) {
                            null
                        } else {
                            AutoActivateCondition(
                                kind = autoKind,
                                ssids = ssid.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList(),
                                subscriptionIds = simSubscriptionId?.let { listOf(it) } ?: emptyList(),
                            )
                        }
                        onSave(name.trim(), mode, auto)
                    }
                },
                enabled = name.isNotBlank(),
            ) { Text(s[S.Save]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

@Composable
private fun ImportDialog(
    preview: RuleImportPreview?,
    mode: RuleImportMode,
    onModeChange: (RuleImportMode) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    val s = rememberUiStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.ImportPreview]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == RuleImportMode.Merge, { onModeChange(RuleImportMode.Merge) }, { Text(s[S.Merge]) })
                    FilterChip(mode == RuleImportMode.Replace, { onModeChange(RuleImportMode.Replace) }, { Text(s[S.Replace]) })
                }
                if (preview == null) {
                    Text(s[S.ImportFailed], color = MaterialTheme.colorScheme.error)
                } else {
                    DetailLine(s[S.Added], preview.added.toString())
                    DetailLine(s[S.Updated], preview.updated.toString())
                    DetailLine(s[S.Removed], preview.removed.toString())
                    DetailLine(s[S.Unchanged], preview.unchanged.toString())
                    DetailLine(s[S.Ineffective], preview.ineffective.toString())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = preview != null) { Text(s[S.Done]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun DropdownSetting(
    label: String,
    current: String,
    options: List<Pair<String, String?>>,
    onSelected: (String?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            var expanded by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { expanded = true }) {
                Text(current)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (text, value) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelected(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScheduleDialog(
    schedule: FocusSchedule?,
    onDismiss: () -> Unit,
    onSave: (FocusSchedule) -> Unit,
) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    var days by remember { mutableStateOf(schedule?.days ?: setOf(1, 2, 3, 4, 5)) }
    var startMinute by remember { mutableIntStateOf(schedule?.startMinute ?: 9 * 60) }
    var endMinute by remember { mutableIntStateOf(schedule?.endMinute ?: 18 * 60) }
    val valid = days.isNotEmpty() && startMinute != endMinute

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.FocusSchedule]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s[S.ScheduleDays], style = MaterialTheme.typography.bodyMedium)
                listOf(1..4, 5..7).forEach { range ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        range.forEach { day ->
                            FilterChip(
                                day in days,
                                {
                                    days = if (day in days) days - day else days + day
                                },
                                {
                                    Text(
                                        DayOfWeek.of(day).getDisplayName(
                                            TextStyle.SHORT,
                                            context.resources.configuration.locales[0] ?: Locale.getDefault(),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> startMinute = hour * 60 + minute },
                            startMinute / 60,
                            startMinute % 60,
                            true,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${s[S.ScheduleStart]}：${minutesToText(startMinute)}")
                }
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> endMinute = hour * 60 + minute },
                            endMinute / 60,
                            endMinute % 60,
                            true,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${s[S.ScheduleEnd]}：${minutesToText(endMinute)}")
                }
                Text(
                    s[S.ScheduleHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        FocusSchedule(
                            id = schedule?.id ?: UUID.randomUUID().toString(),
                            days = days,
                            startMinute = startMinute,
                            endMinute = endMinute,
                            enabled = schedule?.enabled ?: true,
                        ),
                    )
                },
                enabled = valid,
            ) { Text(s[S.Save]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

internal fun FocusSchedule.timeLabel(): String = "${minutesToText(startMinute)}–${minutesToText(endMinute)}"

internal fun FocusSchedule.dayLabel(context: Context): String =
    days.sorted().joinToString(" ") { day ->
        DayOfWeek.of(day).getDisplayName(
            TextStyle.SHORT,
            context.resources.configuration.locales[0] ?: Locale.getDefault(),
        )
    }

private fun minutesToText(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
internal fun OverlayPositionDialog(
    initialRatio: Float,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit,
) {
    val s = rememberUiStrings()
    var ratio by remember { mutableFloatStateOf(initialRatio.coerceIn(0f, 1f)) }
    val panelHeight = 72.dp

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.OverlayPosition]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    s.format(S.OverlayPositionPercent, (ratio * 100).roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(16.dp),
                        )
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                val available = (380.dp - panelHeight).toPx().coerceAtLeast(1f)
                                ratio = (ratio + dragAmount / available).coerceIn(0f, 1f)
                            }
                        },
                ) {
                    val available = maxHeight - panelHeight
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    0,
                                    (available * ratio).roundToPx(),
                                )
                            }
                            .fillMaxWidth()
                            .height(panelHeight)
                            .padding(horizontal = 12.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(16.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "PocketGate",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(
                    s[S.OverlayCustomHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ratio) }) { Text(s[S.Save]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

@Composable
private fun ReleaseNotesDialog(
    currentVersion: String,
    history: Boolean,
    onToggleHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = rememberUiStrings()
    val current = ReleaseNotes.current(currentVersion)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (history) s[S.ReleaseNotesHistory] else "${s[S.ReleaseNotes]} · ${current?.version ?: currentVersion}",
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (history) {
                    ReleaseNotes.all.forEach { note -> ReleaseNoteSection(note, s) }
                } else if (current != null) {
                    ReleaseNoteSection(current, s)
                } else {
                    Text(
                        s[S.NoReleaseNotes],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Close]) }
        },
        dismissButton = {
            TextButton(onClick = onToggleHistory) {
                Text(if (history) s[S.Back] else s[S.ReleaseNotesHistory])
            }
        },
    )
}

@Composable
private fun ReleaseNoteSection(
    note: ReleaseNote,
    s: UiStrings,
) {
    val language = s.noteLanguage
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${note.version} · ${note.date}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (note.features.isNotEmpty()) {
            Text(s[S.NewFeatures], style = MaterialTheme.typography.labelLarge)
            note.features.forEach { Text("• ${it[language]}", style = MaterialTheme.typography.bodySmall) }
        }
        if (note.improvements.isNotEmpty()) {
            Text(s[S.Improvements], style = MaterialTheme.typography.labelLarge)
            note.improvements.forEach { Text("• ${it[language]}", style = MaterialTheme.typography.bodySmall) }
        }
        if (note.notes.isNotEmpty()) {
            note.notes.forEach {
                Text(
                    "• ${it[language]}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun DetailLine(label: String, value: String) {
    Row {
        Text(
            "$label：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun DirectAppsDialog(
    title: String,
    apps: List<InstalledApp>,
    initial: Set<String>,
    allowedByRulePackages: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val s = rememberUiStrings()
    var query by remember { mutableStateOf("") }
    var onlyRuleAllowed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(initial) }
    val filtered = remember(query, onlyRuleAllowed, apps, allowedByRulePackages) {
        VpnRoutingPlanner.filterPickerApps(
            apps = apps,
            query = query,
            onlyRuleAllowed = onlyRuleAllowed,
            allowedByRulePackages = allowedByRulePackages,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(s[S.SearchApps]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !onlyRuleAllowed,
                        onClick = { onlyRuleAllowed = false },
                        label = { Text("${s[S.FilterAll]} (${apps.size})") },
                    )
                    FilterChip(
                        selected = onlyRuleAllowed,
                        onClick = { onlyRuleAllowed = !onlyRuleAllowed },
                        label = { Text("${s[S.FilterRuleAllowed]} (${allowedByRulePackages.size})") },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.format(S.DirectAppsCount, selected.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            selected = VpnRoutingPlanner.selectAllVisible(
                                selected,
                                filtered,
                            )
                        },
                        enabled = filtered.isNotEmpty(),
                    ) {
                        Text(s[S.SelectAll])
                    }
                    TextButton(
                        onClick = {
                            selected = VpnRoutingPlanner.clearVisibleOrAll(
                                selected,
                                filtered,
                            )
                        },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text(s[S.ClearSelection])
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (filtered.isEmpty()) {
                    Text(
                        s[S.NoApps],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = app.packageName in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (checked) selected - app.packageName else selected + app.packageName
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (checked) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) { Text(s[S.Save]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

internal fun toolbarStyleLabel(
    s: UiStrings,
    style: ToolbarStyle,
): String = when (style) {
    ToolbarStyle.ClassicSolid -> s[S.ToolbarClassicSolid]
    ToolbarStyle.ClassicFrosted -> s[S.ToolbarClassicFrosted]
    ToolbarStyle.FloatingSolid -> s[S.ToolbarFloatingSolid]
    ToolbarStyle.FloatingGlass -> s[S.ToolbarFloatingGlass]
    ToolbarStyle.FloatingFrosted -> s[S.ToolbarFloatingFrosted]
    ToolbarStyle.FloatingTransparent -> s[S.ToolbarFloatingTransparent]
}

internal fun updateSourceLabel(s: UiStrings, source: UpdateSource): String =
    when (source) {
        UpdateSource.GitHub -> s[S.UpdateSourceGitHub]
        UpdateSource.GitLab -> s[S.UpdateSourceGitLab]
        UpdateSource.Gitee -> s[S.UpdateSourceGitee]
        UpdateSource.Custom -> s[S.UpdateSourceCustom]
    }

private val KNOWN_PROXY_KEYWORDS = listOf(
    "flclash", "clash", "v2ray", "nekobox", "singbox", "sfa", "reqable",
    "surfboard", "shadowsocks", "hiddify", "karing", "mihomo", "xray", "kitsunebi", "igniter",
)

private fun isLikelyProxyApp(app: InstalledApp): Boolean {
    val pkg = app.packageName.lowercase()
    val lbl = app.label.lowercase()
    return KNOWN_PROXY_KEYWORDS.any { pkg.contains(it) || lbl.contains(it) }
}

@Composable
internal fun ProxyAppDialog(
    apps: List<InstalledApp>,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val s = rememberUiStrings()
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        val base = if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        // 將常見代理應用置頂，方便一鍵排除避免 VPN 回環。
        base.sortedByDescending { isLikelyProxyApp(it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.ProxyApp]) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(s[S.SearchApps]) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(null) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s[S.Unset], style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(filtered, key = { it.packageName }) { app ->
                        val suggested = isLikelyProxyApp(app)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(app.packageName) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (suggested) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    if (suggested) {
                                        Text(
                                            s[S.ProxyAppSuggested],
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (app.packageName == selected) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Close]) }
        },
    )
}

private fun themeStyleLabel(s: UiStrings, style: AppThemeStyle): String = when (style) {
    AppThemeStyle.Classic -> s[S.ThemeStyleClassic]
    AppThemeStyle.ObsidianCyber -> s[S.ThemeStyleObsidian]
    AppThemeStyle.FrostMinimal -> s[S.ThemeStyleFrost]
    AppThemeStyle.AuroraGlass -> s[S.ThemeStyleAurora]
    AppThemeStyle.AmoledBlack -> s[S.ThemeStyleAmoled]
    AppThemeStyle.DynamicYou -> s[S.ThemeStyleDynamic]
}

@Composable
private fun ThemeStyleStrip(
    selected: AppThemeStyle,
    seedColor: Color,
    s: UiStrings,
    onSelect: (AppThemeStyle) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppThemeStyle.entries.forEach { style ->
            val isSelected = style == selected
            val previewBg = when (style) {
                AppThemeStyle.ObsidianCyber -> Color(0xFF08090B)
                AppThemeStyle.FrostMinimal -> Color(0xFFF4F6F9)
                AppThemeStyle.AuroraGlass -> Color(0xFF0E1B24)
                AppThemeStyle.AmoledBlack -> Color(0xFF000000)
                AppThemeStyle.DynamicYou -> MaterialTheme.colorScheme.secondaryContainer
                AppThemeStyle.Classic -> MaterialTheme.colorScheme.surface
            }
            val previewCard = when (style) {
                AppThemeStyle.ObsidianCyber -> Color(0xFF161B24)
                AppThemeStyle.FrostMinimal -> Color(0xFFFFFFFF)
                AppThemeStyle.AuroraGlass -> Color(0xFF1D3140)
                AppThemeStyle.AmoledBlack -> Color(0xFF121212)
                AppThemeStyle.DynamicYou -> MaterialTheme.colorScheme.surface
                AppThemeStyle.Classic -> MaterialTheme.colorScheme.surfaceVariant
            }
            val previewAccent = when (style) {
                AppThemeStyle.ObsidianCyber -> Color(0xFFC9F45A)
                AppThemeStyle.FrostMinimal -> seedColor
                AppThemeStyle.AuroraGlass -> Color(0xFF34D399)
                AppThemeStyle.AmoledBlack -> Color(0xFF69F0AE)
                AppThemeStyle.DynamicYou -> MaterialTheme.colorScheme.primary
                AppThemeStyle.Classic -> seedColor
            }
            val borderColor = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            }
            Column(
                modifier = Modifier
                    .width(86.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
                    .clickable { onSelect(style) }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (style == AppThemeStyle.AuroraGlass) {
                                Brush.linearGradient(listOf(Color(0xFF0E2A2F), Color(0xFF102438)))
                            } else {
                                Brush.verticalGradient(listOf(previewBg, previewBg))
                            },
                        )
                        .padding(6.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(previewCard),
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 5.dp)
                                    .width(24.dp)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(previewAccent),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(previewCard),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(previewCard),
                            )
                        }
                    }
                }
                Text(
                    themeStyleLabel(s, style),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
