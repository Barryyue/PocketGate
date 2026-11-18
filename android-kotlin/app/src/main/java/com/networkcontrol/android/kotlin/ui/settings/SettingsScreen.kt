package com.networkcontrol.android.kotlin.ui.settings

import android.content.Context
import android.content.Intent
import android.app.TimePickerDialog
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.BuildConfig
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.InstalledApp
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.RuleImportMode
import com.networkcontrol.android.kotlin.core.model.RuleImportPreview
import com.networkcontrol.android.kotlin.core.model.ToolbarStyle
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.focus.FocusSchedule
import com.networkcontrol.android.kotlin.core.model.SpeedIslandContent
import com.networkcontrol.android.kotlin.core.model.SpeedIslandMode
import com.networkcontrol.android.kotlin.core.release.ReleaseNote
import com.networkcontrol.android.kotlin.core.release.ReleaseNotes
import com.networkcontrol.android.kotlin.core.update.UpdateChecker
import com.networkcontrol.android.kotlin.core.update.UpdateInfo
import com.networkcontrol.android.kotlin.core.update.UpdateSource
import com.networkcontrol.android.kotlin.platform.Socks5Probe
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.ScreenHeader
import com.networkcontrol.android.kotlin.ui.common.SectionCard
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    backend: AppBackend,
    onOpenIslandSettings: () -> Unit = {},
    onOpenBlocklists: () -> Unit = {},
) {
    val guard = com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction.current
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
    var toolbarStyle by remember { mutableStateOf(backend.toolbarStyle()) }
    val focusSchedules by backend.focusSchedules.collectAsStateWithLifecycle()
    var overlayKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        overlayKey++
        backend.refreshSpeedIsland()
    }
    val canDrawOverlays = remember(overlayKey) { backend.canDrawOverlays() }
    var promptMode by remember { mutableStateOf(backend.promptPresentationMode()) }
    var promptPosition by remember { mutableStateOf(backend.promptOverlayPosition()) }
    var promptRatio by remember { mutableFloatStateOf(backend.promptOverlayCustomRatio()) }
    var showOverlayPositionDialog by remember { mutableStateOf(false) }
    var speedIsland by remember { mutableStateOf(backend.speedIslandEnabled()) }
    var islandOpacity by remember { mutableFloatStateOf(backend.speedIslandOpacity()) }
    var islandMode by remember { mutableStateOf(backend.speedIslandMode()) }
    var promptDetails by remember { mutableStateOf(backend.promptShowDetails()) }
    var hideOnLock by remember { mutableStateOf(backend.promptHideDetailsOnLockScreen()) }
    var blockOnLock by remember { mutableStateOf(backend.blockOnLockScreen()) }
    var lockWhitelist by remember { mutableStateOf(backend.lockScreenWhitelist()) }
    var showLockWhitelistDialog by remember { mutableStateOf(false) }
    var appLock by remember { mutableStateOf(backend.isAppLockEnabled()) }
    var forwardingEnabled by remember { mutableStateOf(backend.forwardingEnabled()) }
    var forwardingUpstream by remember { mutableStateOf(backend.forwardingUpstream()) }
    var dnsBypass by remember { mutableStateOf(backend.dnsBypassProtection()) }
    var exitDns by remember { mutableStateOf(backend.exitDnsProvider()) }
    var exitDnsUrl by remember { mutableStateOf(backend.exitDnsCustomUrl()) }
    var exitDnsFallback by remember { mutableStateOf(backend.exitDnsFallback()) }
    val viaProxy = forwardingUpstream == com.networkcontrol.android.kotlin.ForwardingUpstream.SOCKS5
    var socks5Host by remember { mutableStateOf(backend.socks5Host()) }
    var socks5Port by remember { mutableStateOf(backend.socks5Port().toString()) }
    var socks5Udp by remember { mutableStateOf(backend.socks5Udp()) }
    var socks5User by remember { mutableStateOf(backend.socks5Username()) }
    var socks5Pass by remember { mutableStateOf(backend.socks5Password()) }
    var proxyTesting by remember { mutableStateOf(false) }
    var proxyPackage by remember { mutableStateOf(backend.socks5BypassPackage()) }
    var showProxyAppDialog by remember { mutableStateOf(false) }
    var directPackages by remember { mutableStateOf(backend.forwardingDirectPackages()) }
    var showDirectAppsDialog by remember { mutableStateOf(false) }
    // 已安裝應用列表需要 PackageManager：在背景載入，不在主執行緒上卡住界面。
    val pickerApps by androidx.compose.runtime.produceState<List<com.networkcontrol.android.kotlin.core.model.InstalledApp>?>(
        initialValue = null,
        showProxyAppDialog,
        showDirectAppsDialog,
        showLockWhitelistDialog,
    ) {
        if (showProxyAppDialog || showDirectAppsDialog || showLockWhitelistDialog) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { backend.installedApps() }
        }
    }
    val installedCount by androidx.compose.runtime.produceState(initialValue = -1) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { backend.container.appCatalog.getApps().size }
    }
    val forwardingActive by backend.forwardingActive.collectAsStateWithLifecycle()
    var showTimedOption by remember { mutableStateOf(backend.promptShowTimedOption()) }
    var notificationTitle by remember { mutableStateOf(backend.protectionNotificationTitle()) }
    var denyPermanent by remember { mutableStateOf(backend.promptDenyPermanent()) }
    val forwardingAvailable = remember { backend.forwardingAvailable() }
    var editingSchedule by remember { mutableStateOf<FocusSchedule?>(null) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateSource by remember { mutableStateOf(backend.updateSource()) }
    var updateRepo by remember { mutableStateOf(backend.updateRepo()) }
    var updateCustomUrl by remember { mutableStateOf(backend.updateCustomUrl()) }
    var showReleaseNotes by remember { mutableStateOf(false) }
    var showReleaseHistory by remember { mutableStateOf(false) }

    var timeoutText by remember(settings.promptTimeoutSeconds) {
        mutableStateOf(settings.promptTimeoutSeconds.toString())
    }
    var onceBackgroundText by remember { mutableStateOf(backend.onceBackgroundMinutes().toString()) }
    var exitDetection by remember { mutableStateOf(backend.processExitDetection()) }
    var grantingExitDetection by remember { mutableStateOf(false) }
    var showExitGrantGuide by remember { mutableStateOf(false) }
    var timedMinutesText by remember(settings.promptTimedMinutes) {
        mutableStateOf(settings.promptTimedMinutes.toString())
    }
    var editingProfile by remember { mutableStateOf<Profile?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showThemeColorDialog by remember { mutableStateOf(false) }
    var deletingProfile by remember { mutableStateOf<Profile?>(null) }
    var importJson by remember { mutableStateOf<String?>(null) }
    var previewMerge by remember { mutableStateOf<RuleImportPreview?>(null) }
    var previewReplace by remember { mutableStateOf<RuleImportPreview?>(null) }
    var importMode by remember { mutableStateOf(RuleImportMode.Merge) }
    var showImportDialog by remember { mutableStateOf(false) }

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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 20.dp + com.networkcontrol.android.kotlin.ui.LocalBottomContentPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader(s[S.Settings], s[S.SettingsSubtitle]) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s[S.ThemeColor], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(themeColor)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { showThemeColorDialog = true }) {
                        Text(s[S.Edit])
                    }
                }
                Text(s[S.ToolbarStyle], style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolbarStyle.entries.forEach { style ->
                        FilterChip(
                            toolbarStyle == style,
                            {
                                toolbarStyle = style
                                backend.setToolbarStyle(style)
                            },
                            { Text(toolbarStyleLabel(s, style)) },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(s[S.PromptSettings]) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s[S.PromptTimeout], style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedTextField(
                        value = timeoutText,
                        onValueChange = { value ->
                            timeoutText = value.filter { it.isDigit() }.take(4)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                    TextButton(
                        onClick = {
                            val seconds = timeoutText.toIntOrNull() ?: settings.promptTimeoutSeconds
                            backend.updateSettings { it.copy(promptTimeoutSeconds = seconds) }
                            toast(s[S.Done])
                        },
                    ) { Text(s[S.Save]) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s[S.PromptTimedDuration], style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedTextField(
                        value = timedMinutesText,
                        onValueChange = { value ->
                            timedMinutesText = value.filter { it.isDigit() }.take(4)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                    TextButton(
                        onClick = {
                            val minutes = timedMinutesText.toIntOrNull() ?: settings.promptTimedMinutes
                            backend.updateSettings { it.copy(promptTimedMinutes = minutes) }
                            toast(s[S.Done])
                        },
                    ) { Text(s[S.Save]) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s[S.OnceBackgroundMinutes], style = MaterialTheme.typography.bodyMedium)
                        Text(
                            s[S.OnceBackgroundMinutesHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = onceBackgroundText,
                        onValueChange = { value ->
                            onceBackgroundText = value.filter { it.isDigit() }.take(4)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                    TextButton(
                        onClick = {
                            val minutes = onceBackgroundText.toIntOrNull() ?: backend.onceBackgroundMinutes()
                            backend.setOnceBackgroundMinutes(minutes)
                            onceBackgroundText = backend.onceBackgroundMinutes().toString()
                            toast(s[S.Done])
                        },
                    ) { Text(s[S.Save]) }
                }
                // 關閉偵測（DUMP）：已授權時「僅一次」「禁止」在應用真正關閉時才失效。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (exitDetection) s[S.OnceExitDetectionOn] else s[S.OnceExitDetectionOff],
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (exitDetection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!exitDetection) {
                        TextButton(
                            enabled = !grantingExitDetection,
                            onClick = {
                                grantingExitDetection = true
                                scope.launch {
                                    val outcome = backend.grantExitDetection()
                                    grantingExitDetection = false
                                    exitDetection = backend.processExitDetection()
                                    when (outcome) {
                                        com.networkcontrol.android.kotlin.platform.permissions.DumpGrantOutcome.Granted ->
                                            toast(if (exitDetection) s[S.OnceExitGrantOk] else s[S.OnceExitGrantRestart])
                                        com.networkcontrol.android.kotlin.platform.permissions.DumpGrantOutcome.NoTool ->
                                            showExitGrantGuide = true
                                        com.networkcontrol.android.kotlin.platform.permissions.DumpGrantOutcome.Denied ->
                                            toast(s[S.OnceExitGrantDenied])
                                        is com.networkcontrol.android.kotlin.platform.permissions.DumpGrantOutcome.Failed ->
                                            toast(s.format(S.OnceExitGrantFailed, outcome.message))
                                    }
                                }
                            },
                        ) { Text(s[S.OnceExitGrant]) }
                    }
                }
                if (showExitGrantGuide) {
                    val command = remember { backend.exitDetectionAdbCommand() }
                    AlertDialog(
                        onDismissRequest = { showExitGrantGuide = false },
                        title = { Text(s[S.OnceExitGuideTitle]) },
                        text = {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(s[S.OnceExitGuideBody] + "\n\n" + command)
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    context.getSystemService(android.content.ClipboardManager::class.java)
                                        ?.setPrimaryClip(android.content.ClipData.newPlainText("adb", command))
                                    toast(s[S.Done])
                                },
                            ) { Text(s[S.Copy]) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExitGrantGuide = false }) { Text(s[S.Close]) }
                        },
                    )
                }
                SwitchRow(
                    title = s[S.PromptTimedVisible],
                    subtitle = s[S.PromptTimedVisibleHint],
                    checked = showTimedOption,
                    onCheckedChange = {
                        showTimedOption = it
                        backend.setPromptShowTimedOption(it)
                    },
                )
                SwitchRow(
                    title = s[S.PromptDenyPermanent],
                    subtitle = s[S.PromptDenyPermanentHint],
                    checked = denyPermanent,
                    onCheckedChange = {
                        denyPermanent = it
                        backend.setPromptDenyPermanent(it)
                    },
                )
                SwitchRow(
                    title = s[S.PromptDefaultAction],
                    subtitle = if (settings.promptDefaultAction == VerdictAction.Deny) s[S.Deny] else s[S.Allow],
                    checked = settings.promptDefaultAction == VerdictAction.Allow,
                    onCheckedChange = { allow ->
                        backend.updateSettings {
                            it.copy(promptDefaultAction = if (allow) VerdictAction.Allow else VerdictAction.Deny)
                        }
                    },
                )
                SwitchRow(
                    title = s[S.AllowIcmp],
                    checked = settings.allowIcmpByDefault,
                    onCheckedChange = { allow ->
                        backend.updateSettings { it.copy(allowIcmpByDefault = allow) }
                    },
                )
            }
        }

        item {
            SectionCard(s[S.PromptPresentation]) {
                val autoOverlay = promptMode == "auto"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        autoOverlay,
                        {
                            promptMode = "auto"
                            backend.setPromptPresentationMode("auto")
                        },
                        { Text(s[S.PromptPresentationAuto]) },
                    )
                    FilterChip(
                        !autoOverlay,
                        {
                            promptMode = "notification"
                            backend.setPromptPresentationMode("notification")
                        },
                        { Text(s[S.PromptPresentationNotification]) },
                    )
                }
                Text(
                    s[S.PromptPresentationHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (autoOverlay) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s[S.OverlayPermission],
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (canDrawOverlays) s[S.OverlayGranted] else s[S.OverlayDenied],
                            style = MaterialTheme.typography.bodySmall,
                            color = if (canDrawOverlays) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        if (!canDrawOverlays) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { context.startActivity(backend.overlayPermissionIntent()) }) {
                                Text(s[S.OverlayGrant])
                            }
                        }
                    }
                    Text(s[S.OverlayPosition], style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            promptPosition != "bottom",
                            {
                                promptPosition = "top"
                                backend.setPromptOverlayPosition("top")
                            },
                            { Text(s[S.OverlayTop]) },
                        )
                        FilterChip(
                            promptPosition == "bottom",
                            {
                                promptPosition = "bottom"
                                backend.setPromptOverlayPosition("bottom")
                            },
                            { Text(s[S.OverlayBottom]) },
                        )
                        FilterChip(
                            promptPosition == "custom",
                            {
                                promptPosition = "custom"
                                backend.setPromptOverlayPosition("custom")
                            },
                            { Text(s[S.OverlayCustom]) },
                        )
                    }
                    if (promptPosition == "custom") {
                        OutlinedButton(onClick = { showOverlayPositionDialog = true }) {
                            Text(
                                "${s[S.OverlayAdjustPosition]} " +
                                    "(${(promptRatio * 100).roundToInt()}%)",
                            )
                        }
                        Text(
                            s[S.OverlayCustomHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        s[S.OverlayOemHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(s[S.PromptContentSettings], style = MaterialTheme.typography.bodyMedium)
                SwitchRow(
                    title = s[S.ShowPromptDetails],
                    checked = promptDetails,
                    onCheckedChange = {
                        promptDetails = it
                        backend.setPromptShowDetails(it)
                    },
                )
                SwitchRow(
                    title = s[S.HidePromptDetailsOnLock],
                    checked = hideOnLock,
                    onCheckedChange = {
                        hideOnLock = it
                        backend.setPromptHideDetailsOnLockScreen(it)
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
                    val contentLabel = when (backend.speedIslandContent()) {
                        SpeedIslandContent.Speed -> s[S.IslandContentSpeed]
                        SpeedIslandContent.Traffic -> s[S.IslandContentTraffic]
                        SpeedIslandContent.Blocked -> s[S.IslandContentBlocked]
                        SpeedIslandContent.Pending -> s[S.IslandContentPending]
                        SpeedIslandContent.Network -> s[S.IslandContentNetwork]
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenIslandSettings() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s[S.IslandConfig], style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "$contentLabel · $modeLabel · ${(islandOpacity * 100).roundToInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onOpenIslandSettings) { Text(s[S.Edit]) }
                    }
                }
            }
        }

        item {
            SectionCard(s[S.TrustSettings]) {
                SwitchRow(
                    title = s[S.AllowSystemApps],
                    subtitle = s[S.AllowSystemAppsHint],
                    checked = settings.allowSystemAppsByDefault,
                    onCheckedChange = { backend.setAllowSystemAppsByDefault(it) },
                )
                Text(s[S.TrustedPublishers], style = MaterialTheme.typography.bodyMedium)
                if (settings.trustedSigners.isEmpty()) {
                    Text(
                        s[S.NoTrustedPublishers],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    settings.trustedSigners.forEach { signer ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${signer.take(20)}…",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { backend.untrustPublisher(signer) }) {
                                Text(s[S.Delete])
                            }
                        }
                    }
                    OutlinedButton(onClick = { backend.clearTrustedPublishers() }) {
                        Text(s[S.ClearTrustedPublishers])
                    }
                }
            }
        }

        item {
            SectionCard(s[S.Notifications]) {
                SwitchRow(
                    title = s[S.NotificationsEnabled],
                    checked = backend.container.preferences.notificationsEnabled,
                    onCheckedChange = { backend.setNotificationsEnabled(it) },
                )
                OutlinedTextField(
                    value = notificationTitle,
                    onValueChange = { notificationTitle = it.take(80) },
                    label = { Text(s[S.NotificationTitle]) },
                    placeholder = { Text(s[S.ProtectionOn]) },
                    supportingText = { Text(s[S.NotificationTitleHint]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            notificationTitle = ""
                            backend.setProtectionNotificationTitle("")
                            toast(s[S.Reset])
                        },
                    ) { Text(s[S.Reset]) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            backend.setProtectionNotificationTitle(notificationTitle)
                            toast(s[S.Done])
                        },
                    ) { Text(s[S.Save]) }
                }
                OutlinedButton(onClick = { context.startActivity(backend.openNotificationSettings()) }) {
                    Text(s[S.OpenNotificationSettings])
                }
            }
        }

        item {
            SectionCard(s[S.VpnSettings]) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s[S.LockScreenWhitelist],
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                s.format(S.LockScreenWhitelistCount, lockWhitelist.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showLockWhitelistDialog = true }) {
                            Text(s[S.Select])
                        }
                    }
                    Text(
                        s[S.LockScreenWhitelistHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SwitchRow(
                    title = s[S.AppLock],
                    subtitle = s[S.AppLockHint],
                    checked = appLock,
                    onCheckedChange = { enabled ->
                        if (enabled && !backend.isDeviceSecure()) {
                            toast(s[S.AppLockNoCredential])
                        } else if (!enabled) {
                            // 關閉應用鎖本身就是敏感操作
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
                OutlinedButton(onClick = { context.startActivity(backend.openAlwaysOnVpnSettings()) }) {
                    Text(s[S.AlwaysOnVpn])
                }
                OutlinedButton(onClick = { context.startActivity(backend.openBatteryOptimizationSettings()) }) {
                    Text(s[S.BatteryOptimization])
                }
                Text(
                    s[S.VpnConsentRequired],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(s[S.FocusSchedule]) {
                Text(
                    s[S.ScheduleHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (focusSchedules.isEmpty()) {
                    Text(
                        s[S.NoFocusSchedules],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                focusSchedules.forEach { schedule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${schedule.dayLabel(context)} ${schedule.timeLabel()}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Switch(
                            checked = schedule.enabled,
                            onCheckedChange = { backend.setFocusScheduleEnabled(schedule.id, it) },
                        )
                        TextButton(onClick = { backend.deleteFocusSchedule(schedule.id) }) {
                            Text(s[S.Delete])
                        }
                    }
                }
                OutlinedButton(onClick = {
                    editingSchedule = null
                    showScheduleDialog = true
                }) { Text(s[S.AddSchedule]) }
            }
        }

        item {
            SectionCard(s[S.DataSettings]) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s[S.UsageAccess], style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (backend.hasTrafficAccess()) s[S.UsageAccessGranted] else s[S.UsageAccessDenied],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!backend.hasTrafficAccess()) {
                        OutlinedButton(onClick = { context.startActivity(backend.trafficAccessIntent()) }) {
                            Text(s[S.GrantAccess])
                        }
                    }
                }
                val appListKey = remember { mutableIntStateOf(0) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { appListKey.intValue++ }
                val appList by produceState<com.networkcontrol.android.kotlin.engine.apps.AppCatalog.AppListVisibility?>(
                    initialValue = null,
                    key1 = appListKey.intValue,
                ) {
                    value = withContext(Dispatchers.IO) { backend.appListVisibility() }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s[S.AppListPermission], style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (appList?.restricted == true) s[S.AppListPermissionRestricted] else s[S.AppListPermissionOk],
                            style = MaterialTheme.typography.bodySmall,
                            color = if (appList?.restricted == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (appList?.restricted == true) {
                        OutlinedButton(onClick = { context.startActivity(backend.openAppSettings()) }) {
                            Text(s[S.GoToSettings])
                        }
                    }
                }
            }
        }

        item {
            SectionCard(s[S.ProfileSettings]) {
                profiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        active = profile.id == runtime.activeProfileId,
                        s = s,
                        onActivate = { backend.activateProfile(profile.id) },
                        onEdit = {
                            editingProfile = profile
                            showProfileDialog = true
                        },
                        onDelete = { deletingProfile = profile },
                    )
                }
                OutlinedButton(
                    onClick = {
                        editingProfile = null
                        showProfileDialog = true
                    },
                ) { Text(s[S.AddProfile]) }
                OutlinedButton(onClick = { backend.createFocusProfile(copyFromCurrent = true) }) {
                    Text(s[S.CreateFocusProfile])
                }
            }
        }

        item {
            SectionCard(s[S.Transfer]) {
                OutlinedButton(onClick = { exportLauncher.launch("networkcontrol-rules.json") }) {
                    Text(s[S.ExportRules])
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                    Text(s[S.ImportRules])
                }
            }
        }

        item {
            SectionCard(s[S.Diagnostics]) {
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
            }
        }

        item {
            SectionCard(s[S.ForwardingMode]) {
                Text(
                    s[S.ForwardingHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (forwardingAvailable) s[S.ForwardingAvailable] else s[S.ForwardingUnavailable],
                    style = MaterialTheme.typography.bodySmall,
                    color = if (forwardingAvailable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
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
                if (forwardingEnabled) {
                    Text(
                        if (forwardingActive) s[S.ForwardingStatusActive] else s[S.ForwardingStatusInactive],
                        style = MaterialTheme.typography.bodySmall,
                        color = if (forwardingActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(s[S.ForwardingUpstream], style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !viaProxy,
                            onClick = {
                                forwardingUpstream = com.networkcontrol.android.kotlin.ForwardingUpstream.DIRECT
                                backend.setForwardingUpstream(forwardingUpstream)
                            },
                            label = { Text(s[S.UpstreamDirect]) },
                        )
                        FilterChip(
                            selected = viaProxy,
                            onClick = {
                                forwardingUpstream = com.networkcontrol.android.kotlin.ForwardingUpstream.SOCKS5
                                backend.setForwardingUpstream(forwardingUpstream)
                            },
                            label = { Text(s[S.UpstreamSocks5]) },
                        )
                    }
                    if (!viaProxy) {
                        Text(
                            s[S.UpstreamDirectHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (viaProxy) {
                    Text(
                        s[S.ForwardingGuide],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = socks5Host,
                        onValueChange = { socks5Host = it },
                        label = { Text(s[S.Socks5Host]) },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = socks5Port,
                            onValueChange = {
                                socks5Port = it.filter { char -> char.isDigit() }.take(5)
                            },
                            label = { Text(s[S.Socks5Port]) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                backend.setSocks5Host(socks5Host)
                                backend.setSocks5Port(socks5Port.toIntOrNull() ?: 7890)
                                backend.setSocks5Credentials(socks5User, socks5Pass)
                                toast(s[S.Done])
                            },
                        ) { Text(s[S.Save]) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = socks5User,
                            onValueChange = { socks5User = it },
                            label = { Text(s[S.Socks5Username]) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = socks5Pass,
                            onValueChange = { socks5Pass = it },
                            label = { Text(s[S.Socks5Password]) },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    }
                    SwitchRow(
                        title = s[S.Socks5Udp],
                        subtitle = s[S.Socks5UdpHint],
                        checked = socks5Udp,
                        onCheckedChange = {
                            socks5Udp = it
                            backend.setSocks5Udp(it)
                        },
                    )
                    SwitchRow(
                        title = s[S.DnsBypassProtection],
                        subtitle = s[S.DnsBypassProtectionHint],
                        checked = dnsBypass,
                        onCheckedChange = {
                            val value = it
                            guard.run {
                                dnsBypass = value
                                backend.setDnsBypassProtection(value)
                            }
                        },
                    )
                    if (!viaProxy) {
                        Text(s[S.ExitDns], style = MaterialTheme.typography.bodyMedium)
                        Text(
                            s[S.ExitDnsHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val dnsOptions = listOf("system" to s[S.ExitDnsSystem]) +
                            com.networkcontrol.android.kotlin.engine.dns.DohProviders.all.map { it.id to it.name } +
                            listOf("custom" to s[S.ExitDnsCustom])
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            dnsOptions.forEach { (id, label) ->
                                FilterChip(
                                    selected = exitDns == id,
                                    onClick = {
                                        exitDns = id
                                        backend.setExitDnsProvider(id)
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                        if (exitDns == "custom") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = exitDnsUrl,
                                    onValueChange = { exitDnsUrl = it.trim() },
                                    label = { Text(s[S.ExitDnsCustomUrl]) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    backend.setExitDnsCustomUrl(exitDnsUrl)
                                    toast(s[S.Done])
                                }) { Text(s[S.Save]) }
                            }
                        }
                        if (exitDns != "system") {
                            SwitchRow(
                                title = s[S.ExitDnsFallback],
                                subtitle = s[S.ExitDnsFallbackHint],
                                checked = exitDnsFallback,
                                onCheckedChange = {
                                    exitDnsFallback = it
                                    backend.setExitDnsFallback(it)
                                },
                            )
                        }
                    }
                    if (viaProxy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s[S.ProxyApp], style = MaterialTheme.typography.bodyMedium)
                            Text(
                                proxyPackage ?: s[S.Unset],
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showProxyAppDialog = true }) {
                            Text(s[S.Select])
                        }
                    }
                    Text(
                        s[S.ProxyAppHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s[S.DirectApps], style = MaterialTheme.typography.bodyMedium)
                            Text(
                                s.format(S.DirectAppsCount, directPackages.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showDirectAppsDialog = true }) {
                            Text(s[S.Select])
                        }
                    }
                    Text(
                        s[S.DirectAppsHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (viaProxy) {
                    OutlinedButton(
                        onClick = {
                            proxyTesting = true
                            scope.launch {
                                val error = Socks5Probe.test(
                                    host = socks5Host,
                                    port = socks5Port.toIntOrNull() ?: 7890,
                                    username = socks5User.trim().ifEmpty { null },
                                    password = socks5Pass,
                                )
                                proxyTesting = false
                                toast(
                                    if (error == null) {
                                        s[S.ProxyTestSuccess]
                                    } else {
                                        s.format(S.ProxyTestFailed, error)
                                    },
                                )
                            }
                        },
                        enabled = !proxyTesting,
                    ) {
                        Text(if (proxyTesting) s[S.Loading] else s[S.ProxyTest])
                    }
                    Text(
                        s[S.ForwardingDnsHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
                    Text(
                        s[S.ForwardingAbiHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            val blocklistState by backend.blocklistState.collectAsStateWithLifecycle()
            val blocklistMatcher by backend.blocklistMatcher.collectAsStateWithLifecycle()
            SectionCard(s[S.Blocklists]) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenBlocklists() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s[S.BlocklistsSubtitle], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (blocklistState.enabled) {
                                s.format(
                                    S.BlocklistsActive,
                                    java.text.NumberFormat.getIntegerInstance().format(blocklistMatcher.totalEntries),
                                    blocklistState.lists.count { it.enabled && it.entries > 0 },
                                )
                            } else {
                                s[S.Off]
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (blocklistState.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenBlocklists) { Text(s[S.Edit]) }
                }
            }
        }

        item {
            SectionCard(s[S.About]) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showReleaseHistory = false
                            showReleaseNotes = true
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailLine(s[S.Version], "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    Spacer(Modifier.weight(1f))
                    Text(
                        s[S.ViewDetails],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                DetailLine(s[S.Engine], "AndroidVpn")
                if (backend.isWorkProfile()) {
                    DetailLine(s[S.WorkProfile], "✓")
                    Text(
                        s[S.WorkProfileHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DetailLine(s[S.Status], "${runtime.mode.name} / ${backend.protectionStatus().name}")
                DetailLine(s[S.SkippedRules], plan.skippedRules.size.toString())
                DetailLine(s[S.AppsCount], if (installedCount < 0) "…" else installedCount.toString())
                Text(s[S.UpdateSource], style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateSource.entries.forEach { source ->
                        FilterChip(
                            updateSource == source,
                            {
                                updateSource = source
                                backend.setUpdateSource(source)
                            },
                            { Text(updateSourceLabel(s, source)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = if (updateSource == UpdateSource.Custom) updateCustomUrl else updateRepo,
                    onValueChange = {
                        if (updateSource == UpdateSource.Custom) updateCustomUrl = it
                        else updateRepo = it
                    },
                    label = {
                        Text(
                            if (updateSource == UpdateSource.Custom) {
                                s[S.UpdateCustomUrl]
                            } else {
                                s[S.UpdateRepo]
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s[S.UpdateRepoHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            backend.setUpdateSource(updateSource)
                            backend.setUpdateRepo(updateRepo)
                            backend.setUpdateCustomUrl(updateCustomUrl)
                            toast(s[S.Done])
                        },
                    ) { Text(s[S.Save]) }
                }
                OutlinedButton(
                    onClick = {
                        if (checkingUpdate) return@OutlinedButton
                        checkingUpdate = true
                        scope.launch {
                            backend.setUpdateSource(updateSource)
                            backend.setUpdateRepo(updateRepo)
                            backend.setUpdateCustomUrl(updateCustomUrl)
                            val info = UpdateChecker.check(
                                currentVersion = BuildConfig.VERSION_NAME,
                                source = updateSource,
                                repo = updateRepo,
                                customUrl = updateCustomUrl,
                            )
                            updateInfo = info
                            checkingUpdate = false
                            when {
                                info.error != null -> toast(s.format(S.UpdateFailed, info.error))
                                info.hasUpdate -> toast(s.format(S.UpdateAvailable, info.latestVersion))
                                else -> toast(s[S.UpToDate])
                            }
                        }
                    },
                    enabled = !checkingUpdate,
                ) {
                    Text(if (checkingUpdate) s[S.Loading] else s[S.CheckUpdates])
                }
                updateInfo?.takeIf { it.hasUpdate }?.let { info ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.format(S.UpdateAvailable, info.latestVersion.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            info.downloadUrl?.let {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(it))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text(s[S.UpdateDownload]) }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showProfileDialog) {
        ProfileDialog(
            profile = editingProfile,
            currentNetwork = backend.container.network.current,
            loadSims = { backend.subscriptions() },
            onDismiss = { showProfileDialog = false },
            onSave = { name, mode, auto ->
                val current = editingProfile
                if (current == null) backend.createProfile(name, mode, auto)
                else backend.updateProfile(current.id, name, mode, auto)
                showProfileDialog = false
            },
        )
    }

    if (showThemeColorDialog) {
        ThemeColorDialog(
            current = themeColor,
            onDismiss = { showThemeColorDialog = false },
            onSelect = { backend.setThemeColor(it) },
        )
    }

    val proxyApps = pickerApps
    if (showProxyAppDialog && proxyApps != null) {
        ProxyAppDialog(
            apps = proxyApps,
            selected = proxyPackage,
            onDismiss = { showProxyAppDialog = false },
            onSelect = { packageName ->
                proxyPackage = packageName
                backend.setSocks5BypassPackage(packageName)
                showProxyAppDialog = false
            },
        )
    }

    val directApps = pickerApps
    val allowedByRulePackages = remember(directApps, ruleSet.version, network) {
        directApps?.let { backend.allowedByRulePackages(it) } ?: emptySet()
    }
    if (showDirectAppsDialog && directApps != null) {
        DirectAppsDialog(
            title = s[S.DirectApps],
            apps = directApps,
            initial = directPackages,
            allowedByRulePackages = allowedByRulePackages,
            onDismiss = { showDirectAppsDialog = false },
            onSave = { packages ->
                directPackages = packages
                guard.run { backend.setForwardingDirectPackages(packages) }
                showDirectAppsDialog = false
            },
        )
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

    if (showScheduleDialog) {
        ScheduleDialog(
            schedule = editingSchedule,
            onDismiss = { showScheduleDialog = false },
            onSave = { schedule ->
                backend.saveFocusSchedule(schedule)
                showScheduleDialog = false
            },
        )
    }

    if (showOverlayPositionDialog) {
        OverlayPositionDialog(
            initialRatio = promptRatio,
            onDismiss = { showOverlayPositionDialog = false },
            onSave = { ratio ->
                promptRatio = ratio
                backend.setPromptOverlayCustomRatio(ratio)
                showOverlayPositionDialog = false
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

    deletingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text(s[S.DeleteProfile]) },
            text = { Text(s[S.DeleteProfileConfirm]) },
            confirmButton = {
                TextButton(onClick = {
                    backend.deleteProfile(profile.id)
                    deletingProfile = null
                }) { Text(s[S.Delete]) }
            },
            dismissButton = {
                TextButton(onClick = { deletingProfile = null }) { Text(s[S.Cancel]) }
            },
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
private fun ProfileRow(
    profile: Profile,
    active: Boolean,
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
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
                    com.networkcontrol.android.kotlin.core.model.NetworkKind.Wifi -> s[S.Wifi]
                    com.networkcontrol.android.kotlin.core.model.NetworkKind.Cellular -> s[S.Cellular]
                    com.networkcontrol.android.kotlin.core.model.NetworkKind.Wired -> s[S.Wired]
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
private fun ProfileDialog(
    profile: Profile?,
    currentNetwork: com.networkcontrol.android.kotlin.core.model.NetworkContext,
    loadSims: () -> List<com.networkcontrol.android.kotlin.core.model.SimInfo>,
    onDismiss: () -> Unit,
    onSave: (String, FilterMode, com.networkcontrol.android.kotlin.core.model.AutoActivateCondition?) -> Unit,
) {
    val s = rememberUiStrings()
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var mode by remember { mutableStateOf(profile?.mode ?: FilterMode.Ask) }
    var autoKind by remember { mutableStateOf(profile?.autoActivate?.kind) }
    var simSubscriptionId by remember { mutableStateOf(profile?.autoActivate?.subscriptionIds?.firstOrNull()) }
    var sims by remember { mutableStateOf(loadSims()) }
    var ssid by remember { mutableStateOf(profile?.autoActivate?.ssids?.firstOrNull().orEmpty()) }
    val context = LocalContext.current
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
                        autoKind == com.networkcontrol.android.kotlin.core.model.NetworkKind.Wifi,
                        { autoKind = com.networkcontrol.android.kotlin.core.model.NetworkKind.Wifi },
                        { Text(s[S.Wifi]) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        autoKind == com.networkcontrol.android.kotlin.core.model.NetworkKind.Cellular,
                        { autoKind = com.networkcontrol.android.kotlin.core.model.NetworkKind.Cellular },
                        { Text(s[S.Cellular]) },
                    )
                    FilterChip(
                        autoKind == com.networkcontrol.android.kotlin.core.model.NetworkKind.Wired,
                        { autoKind = com.networkcontrol.android.kotlin.core.model.NetworkKind.Wired },
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
                if (currentNetwork.kind == com.networkcontrol.android.kotlin.core.model.NetworkKind.Wifi &&
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
                if (currentNetwork.kind != com.networkcontrol.android.kotlin.core.model.NetworkKind.None) {
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
                            com.networkcontrol.android.kotlin.core.model.AutoActivateCondition(
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
private fun SwitchRow(
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
private fun DropdownSetting(
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
private fun ScheduleDialog(
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

private fun FocusSchedule.timeLabel(): String = "${minutesToText(startMinute)}–${minutesToText(endMinute)}"

private fun FocusSchedule.dayLabel(context: Context): String =
    days.sorted().joinToString(" ") { day ->
        DayOfWeek.of(day).getDisplayName(
            TextStyle.SHORT,
            context.resources.configuration.locales[0] ?: Locale.getDefault(),
        )
    }

private fun minutesToText(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun parseMinutes(text: String): Int? {
    val parts = text.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

@Composable
private fun OverlayPositionDialog(
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
                            androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
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
                                androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "NetworkControl",
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
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
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
private fun DetailLine(label: String, value: String) {
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
private fun DirectAppsDialog(
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
        com.networkcontrol.android.kotlin.engine.vpn.VpnRoutingPlanner.filterPickerApps(
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
                            selected = com.networkcontrol.android.kotlin.engine.vpn.VpnRoutingPlanner.selectAllVisible(
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
                            selected = com.networkcontrol.android.kotlin.engine.vpn.VpnRoutingPlanner.clearVisibleOrAll(
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

private fun toolbarStyleLabel(
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
    style: ToolbarStyle,
): String = when (style) {
    ToolbarStyle.ClassicSolid -> s[S.ToolbarClassicSolid]
    ToolbarStyle.ClassicFrosted -> s[S.ToolbarClassicFrosted]
    ToolbarStyle.FloatingSolid -> s[S.ToolbarFloatingSolid]
    ToolbarStyle.FloatingGlass -> s[S.ToolbarFloatingGlass]
    ToolbarStyle.FloatingFrosted -> s[S.ToolbarFloatingFrosted]
    ToolbarStyle.FloatingTransparent -> s[S.ToolbarFloatingTransparent]
}

private fun updateSourceLabel(s: com.networkcontrol.android.kotlin.ui.UiStrings, source: UpdateSource): String =
    when (source) {
        UpdateSource.GitHub -> s[S.UpdateSourceGitHub]
        UpdateSource.GitLab -> s[S.UpdateSourceGitLab]
        UpdateSource.Gitee -> s[S.UpdateSourceGitee]
        UpdateSource.Custom -> s[S.UpdateSourceCustom]
    }

@Composable
private fun ProxyAppDialog(
    apps: List<InstalledApp>,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val s = rememberUiStrings()
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(app.packageName) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
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
