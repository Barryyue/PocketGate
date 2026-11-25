package com.networkcontrol.android.kotlin.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.WindowFocusBus
import com.networkcontrol.android.kotlin.core.model.InstalledApp
import com.networkcontrol.android.kotlin.core.model.RuleImportMode
import com.networkcontrol.android.kotlin.core.model.RuleImportPreview
import com.networkcontrol.android.kotlin.core.model.ToolbarStyle
import com.networkcontrol.android.kotlin.engine.apps.AppCatalog
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.formatBytes
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 外觀與主題獨立設定頁：語言、明暗模式、視覺風格、主題色、應用圖示與導覽列樣式。
 */
@Composable
fun AppearanceSettingsScreen(backend: AppBackend, onBack: () -> Unit) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    val themeColor by backend.container.theme.color.collectAsStateWithLifecycle()
    val themeStyle by backend.container.theme.themeStyle.collectAsStateWithLifecycle()
    var languageTag by remember { mutableStateOf(backend.container.preferences.languageTag) }
    var themeMode by remember { mutableStateOf(backend.container.preferences.themeMode) }
    var toolbarStyle by remember { mutableStateOf(backend.toolbarStyle()) }
    var appIconStyle by remember { mutableStateOf(backend.appIconStyle()) }
    var autoHideBottomBar by remember { mutableStateOf(backend.autoHideBottomBar()) }
    var speedChartColors by remember { mutableStateOf(backend.speedChartColorScheme()) }
    var showThemeColorDialog by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showToolbarMenu by remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    BackHandler { onBack() }

    SettingsSubScreenScaffold(title = s[S.Appearance], onBack = onBack) {
        item {
            SettingsGroup(s[S.Theme]) {
                SettingsSegmentedRow(
                    title = s[S.Theme],
                    options = listOf(
                        "system" to s[S.ThemeSystem],
                        "light" to s[S.ThemeLight],
                        "dark" to s[S.ThemeDark],
                    ),
                    selected = themeMode,
                    onSelect = { mode ->
                        themeMode = mode
                        backend.setTheme(mode)
                    },
                )
                SettingsDivider(insetWithIcon = false)
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        s[S.ThemeStyle],
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    ThemeStyleStrip(
                        selected = themeStyle,
                        seedColor = themeColor,
                        s = s,
                        onSelect = { backend.setThemeStyle(it) },
                    )
                }
                SettingsDivider(insetWithIcon = false)
                SettingsTile(
                    title = s[S.ThemeColor],
                    trailingCustom = {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(themeColor)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                    },
                    onClick = { showThemeColorDialog = true },
                )
                SettingsDivider(insetWithIcon = false)
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        s[S.AppIconStyle],
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    AppIconSelector(
                        selected = appIconStyle,
                        s = s,
                        onSelect = { icon ->
                            if (icon != appIconStyle) {
                                appIconStyle = icon
                                backend.setAppIconStyle(icon)
                                toast(s[S.AppIconSwitched])
                            }
                        },
                    )
                }
            }
        }

        item {
            SettingsGroup(s[S.Language]) {
                val currentLang = when (languageTag) {
                    "zh-CN" -> s[S.ChineseSimplified]
                    "zh-TW" -> s[S.ChineseTraditional]
                    "en-US" -> s[S.English]
                    else -> s[S.SystemDefault]
                }
                Box {
                    SettingsTile(
                        title = s[S.Language],
                        trailingText = currentLang,
                        onClick = { showLanguageMenu = true },
                    )
                    DropdownMenu(
                        expanded = showLanguageMenu,
                        onDismissRequest = { showLanguageMenu = false },
                    ) {
                        listOf(
                            s[S.SystemDefault] to null,
                            s[S.ChineseSimplified] to "zh-CN",
                            s[S.ChineseTraditional] to "zh-TW",
                            s[S.English] to "en-US",
                        ).forEach { (label, tag) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    languageTag = tag
                                    backend.setLanguage(tag)
                                    showLanguageMenu = false
                                },
                            )
                        }
                    }
                }
                SettingsDivider(insetWithIcon = false)
                Box {
                    SettingsTile(
                        title = s[S.ToolbarStyle],
                        trailingText = toolbarStyleLabel(s, toolbarStyle),
                        onClick = { showToolbarMenu = true },
                    )
                    DropdownMenu(
                        expanded = showToolbarMenu,
                        onDismissRequest = { showToolbarMenu = false },
                    ) {
                        ToolbarStyle.entries.forEach { style ->
                            DropdownMenuItem(
                                text = { Text(toolbarStyleLabel(s, style)) },
                                onClick = {
                                    toolbarStyle = style
                                    backend.setToolbarStyle(style)
                                    showToolbarMenu = false
                                },
                            )
                        }
                    }
                }
                SettingsDivider(insetWithIcon = false)
                SettingsSwitchTile(
                    title = s[S.AutoHideBottomBar],
                    subtitle = s[S.AutoHideBottomBarHint],
                    checked = autoHideBottomBar,
                    onCheckedChange = { enabled ->
                        autoHideBottomBar = enabled
                        backend.setAutoHideBottomBar(enabled)
                    },
                )
                SettingsDivider(insetWithIcon = false)
                SettingsSegmentedRow(
                    title = s[S.SpeedChartColors],
                    options = listOf(
                        "emerald_sky" to s[S.SpeedChartEmeraldSky],
                        "emerald_amber" to s[S.SpeedChartEmeraldAmber],
                    ),
                    selected = speedChartColors,
                    onSelect = { scheme ->
                        speedChartColors = scheme
                        backend.setSpeedChartColorScheme(scheme)
                    },
                )
            }
        }
    }

    if (showThemeColorDialog) {
        ThemeColorDialog(
            current = themeColor,
            onDismiss = { showThemeColorDialog = false },
            onSelect = { backend.setThemeColor(it) },
        )
    }
}

/**
 * 防護與網路策略獨立設定頁：集中系統應用放行、可信發佈者、鎖屏斷網、區網、漫遊、ICMP 與分身策略。
 */
@Composable
fun ProtectionSettingsScreen(backend: AppBackend, onBack: () -> Unit) {
    val guard = LocalSensitiveAction.current
    val s = rememberUiStrings()
    val settings by backend.settings.collectAsStateWithLifecycle()
    val ruleSet by backend.container.rules.ruleSet.collectAsStateWithLifecycle()
    val network by backend.network.collectAsStateWithLifecycle()

    var blockOnLock by remember { mutableStateOf(backend.blockOnLockScreen()) }
    var lockWhitelist by remember { mutableStateOf(backend.lockScreenWhitelist()) }
    var showLockWhitelistDialog by remember { mutableStateOf(false) }
    var allowLan by remember { mutableStateOf(backend.allowLan()) }
    var blockOnRoaming by remember { mutableStateOf(backend.blockOnRoaming()) }
    var cloneIndependent by remember { mutableStateOf(!backend.cloneAppsInheritPrimary()) }
    var showTrustedPublishersDialog by remember { mutableStateOf(false) }

    val pickerApps by produceState<List<InstalledApp>?>(
        initialValue = null,
        showLockWhitelistDialog,
    ) {
        if (showLockWhitelistDialog) {
            value = withContext(Dispatchers.IO) { backend.installedApps() }
        }
    }

    BackHandler { onBack() }

    SettingsSubScreenScaffold(title = s[S.ProtectionPolicies], onBack = onBack) {
        item {
            SettingsGroup(s[S.VpnSettings]) {
                SettingsSwitchTile(
                    title = s[S.AllowSystemApps],
                    subtitle = s[S.AllowSystemAppsHint],
                    checked = settings.allowSystemAppsByDefault,
                    onCheckedChange = { enabled ->
                        guard.run { backend.setAllowSystemAppsByDefault(enabled) }
                    },
                )
                SettingsDivider(insetWithIcon = false)
                SettingsTile(
                    title = s[S.TrustedPublishers],
                    subtitle = if (settings.trustedSigners.isEmpty()) {
                        s[S.NoTrustedPublishers]
                    } else {
                        "${settings.trustedSigners.size}"
                    },
                    showChevron = settings.trustedSigners.isNotEmpty(),
                    onClick = if (settings.trustedSigners.isNotEmpty()) {
                        { showTrustedPublishersDialog = true }
                    } else {
                        null
                    },
                )
                SettingsDivider(insetWithIcon = false)
                SettingsSwitchTile(
                    title = s[S.CloneIndependent],
                    subtitle = s[S.CloneIndependentHint],
                    checked = cloneIndependent,
                    onCheckedChange = { independent ->
                        guard.run {
                            cloneIndependent = independent
                            backend.setCloneAppsInheritPrimary(!independent)
                        }
                    },
                )
            }
        }

        item {
            SettingsGroup(s[S.Protection]) {
                SettingsSwitchTile(
                    title = s[S.BlockOnLock],
                    subtitle = s[S.BlockOnLockHint],
                    checked = blockOnLock,
                    onCheckedChange = { enabled ->
                        guard.run {
                            blockOnLock = enabled
                            backend.setBlockOnLockScreen(enabled)
                        }
                    },
                )
                if (blockOnLock) {
                    SettingsDivider(insetWithIcon = false)
                    SettingsTile(
                        title = s[S.LockScreenWhitelist],
                        subtitle = s.format(S.LockScreenWhitelistCount, lockWhitelist.size),
                        onClick = { showLockWhitelistDialog = true },
                    )
                }
                SettingsDivider(insetWithIcon = false)
                SettingsSwitchTile(
                    title = s[S.AllowLan],
                    subtitle = s[S.AllowLanHint],
                    checked = allowLan,
                    onCheckedChange = { enabled ->
                        guard.run {
                            allowLan = enabled
                            backend.setAllowLan(enabled)
                        }
                    },
                )
                SettingsDivider(insetWithIcon = false)
                SettingsSwitchTile(
                    title = s[S.BlockOnRoaming],
                    subtitle = s[S.BlockOnRoamingHint],
                    checked = blockOnRoaming,
                    onCheckedChange = { enabled ->
                        guard.run {
                            blockOnRoaming = enabled
                            backend.setBlockOnRoaming(enabled)
                        }
                    },
                )
                SettingsDivider(insetWithIcon = false)
                SettingsSwitchTile(
                    title = s[S.AllowIcmp],
                    checked = settings.allowIcmpByDefault,
                    onCheckedChange = { allow ->
                        guard.run {
                            backend.updateSettings { it.copy(allowIcmpByDefault = allow) }
                        }
                    },
                )
            }
        }
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
                guard.run {
                    lockWhitelist = packages
                    backend.setLockScreenWhitelist(packages)
                }
                showLockWhitelistDialog = false
            },
        )
    }
}

/**
 * 系統權限與常駐保活獨立設定頁：6 項紅綠燈體檢 + 始終開啟 VPN 狀態檢查。
 */
@Composable
fun PermissionsSettingsScreen(backend: AppBackend, onBack: () -> Unit) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    var resumeKey by remember { mutableIntStateOf(0) }
    val windowFocusTick by WindowFocusBus.ticks.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumeKey++
    }
    val effectiveResumeKey = resumeKey * 31 + windowFocusTick

    val vpnOk = remember(effectiveResumeKey) { backend.isVpnPrepared() }
    val notifOk = remember(effectiveResumeKey) { backend.notificationsAvailable() }
    val hasTrafficAccess = remember(effectiveResumeKey) { backend.hasTrafficAccess() }
    val canDrawOverlays = remember(effectiveResumeKey) { backend.canDrawOverlays() }
    val batteryOk = remember(effectiveResumeKey) { backend.isIgnoringBatteryOptimizations() }
    val appList by produceState<AppCatalog.AppListVisibility?>(
        initialValue = null,
        key1 = effectiveResumeKey,
    ) {
        value = withContext(Dispatchers.IO) { backend.appListVisibility() }
    }
    val appListRestricted = appList?.restricted == true
    val okCount = listOf(vpnOk, notifOk, hasTrafficAccess, !appListRestricted, canDrawOverlays, batteryOk).count { it }
    val alwaysOnActive = remember(effectiveResumeKey) { backend.isAlwaysOnVpn() }
    val lockdownActive = remember(effectiveResumeKey) { backend.isLockdownVpn() }

    BackHandler { onBack() }

    SettingsSubScreenScaffold(title = s[S.PermissionHealth], onBack = onBack) {
        item {
            SettingsGroup("${s[S.PermissionHealth]} · $okCount / 6") {
                SettingsPermissionTile(
                    title = s[S.PermVpn],
                    statusText = if (vpnOk) s[S.PermissionOk] else s[S.PermissionMissing],
                    ok = vpnOk,
                    actionText = if (vpnOk) null else s[S.GoToSettings],
                    onAction = { context.startActivity(backend.openAlwaysOnVpnSettings()) },
                )
                SettingsDivider(insetWithIcon = false)
                SettingsPermissionTile(
                    title = s[S.PermNotifications],
                    statusText = if (notifOk) s[S.PermissionOk] else s[S.PermissionMissing],
                    ok = notifOk,
                    actionText = if (notifOk) null else s[S.GoToSettings],
                    onAction = { context.startActivity(backend.openNotificationSettings()) },
                )
                SettingsDivider(insetWithIcon = false)
                SettingsPermissionTile(
                    title = s[S.PermUsage],
                    statusText = if (hasTrafficAccess) s[S.UsageAccessGranted] else s[S.UsageAccessDenied],
                    ok = hasTrafficAccess,
                    actionText = if (hasTrafficAccess) null else s[S.GrantAccess],
                    onAction = { context.startActivity(backend.trafficAccessIntent()) },
                )
                SettingsDivider(insetWithIcon = false)
                SettingsPermissionTile(
                    title = s[S.PermAppList],
                    statusText = if (appListRestricted) s[S.AppListPermissionRestricted] else s[S.AppListPermissionOk],
                    ok = !appListRestricted,
                    actionText = if (appListRestricted) s[S.GoToSettings] else null,
                    onAction = { context.startActivity(backend.openAppSettings()) },
                )
                SettingsDivider(insetWithIcon = false)
                SettingsPermissionTile(
                    title = s[S.PermOverlay],
                    statusText = if (canDrawOverlays) s[S.OverlayGranted] else s[S.OverlayDenied],
                    ok = canDrawOverlays,
                    actionText = if (canDrawOverlays) null else s[S.OverlayGrant],
                    onAction = { context.startActivity(backend.overlayPermissionIntent()) },
                )
                SettingsDivider(insetWithIcon = false)
                SettingsPermissionTile(
                    title = s[S.PermBattery],
                    statusText = if (batteryOk) s[S.PermissionOk] else s[S.PermissionMissing],
                    ok = batteryOk,
                    actionText = if (batteryOk) null else s[S.GoToSettings],
                    onAction = { context.startActivity(backend.openBatteryOptimizationSettings()) },
                )
            }
        }

        item {
            SettingsGroup(s[S.AlwaysOnVpn]) {
                SettingsTile(
                    title = s[S.AlwaysOnVpn],
                    subtitle = when {
                        lockdownActive -> s[S.AlwaysOnLockdownWarning]
                        alwaysOnActive -> s[S.AlwaysOnVpnReady]
                        else -> s[S.OpenSystemSettings]
                    },
                    drawableIcon = R.drawable.ic_shield,
                    iconAccent = if (lockdownActive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    subtitleHighlighted = alwaysOnActive && !lockdownActive,
                    onClick = { context.startActivity(backend.openAlwaysOnVpnSettings()) },
                )
            }
        }

        item {
            val guard = LocalSensitiveAction.current
            var extEnabled by remember { mutableStateOf(backend.externalAutomationEnabled()) }
            var extToken by remember { mutableStateOf(backend.externalAutomationToken()) }
            SettingsGroup(s[S.ExternalAutomation]) {
                SettingsSwitchTile(
                    title = s[S.ExternalAutomation],
                    subtitle = s[S.ExternalAutomationHint],
                    checked = extEnabled,
                    onCheckedChange = { enabled ->
                        guard.run {
                            extEnabled = enabled
                            backend.setExternalAutomationEnabled(enabled)
                        }
                    },
                )
                if (extEnabled) {
                    SettingsDivider(insetWithIcon = false)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = extToken,
                            onValueChange = {
                                extToken = it.take(64)
                                backend.setExternalAutomationToken(extToken)
                            },
                            label = { Text(s[S.ExternalAutomationToken]) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = {
                                val snippet = com.networkcontrol.android.kotlin.platform.automation
                                    .ExternalAutomationCommand.exampleSnippet(extToken)
                                context.getSystemService(android.content.ClipboardManager::class.java)
                                    ?.setPrimaryClip(android.content.ClipData.newPlainText("pocketgate-automation", snippet))
                                Toast.makeText(context, s[S.Copied], Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(s[S.ExternalAutomationCopyExample])
                        }
                    }
                }
            }
        }
    }
}

/**
 * 備份、儲存與診斷獨立設定頁：規則與設定備份、WebDAV、記錄保留天數（全寬分段膠囊）與資料庫壓縮。
 */
@Composable
fun DataBackupSettingsScreen(backend: AppBackend, onBack: () -> Unit) {
    val guard = LocalSensitiveAction.current
    val s = rememberUiStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by backend.settings.collectAsStateWithLifecycle()
    val offlineMode by backend.offlineModeFlow.collectAsStateWithLifecycle()

    var dbSizeBytes by remember { mutableStateOf(backend.activityDbSizeBytes()) }
    var showWebDavDialog by remember { mutableStateOf(false) }
    var importJson by remember { mutableStateOf<String?>(null) }
    var previewMerge by remember { mutableStateOf<RuleImportPreview?>(null) }
    var previewReplace by remember { mutableStateOf<RuleImportPreview?>(null) }
    var importMode by remember { mutableStateOf(RuleImportMode.Merge) }
    var showImportDialog by remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

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
                    importMode = RuleImportMode.Merge
                    showImportDialog = true
                }
            }.onFailure { toast(s.format(S.ImportFailed, it.message.orEmpty())) }
        }
    }

    BackHandler { onBack() }

    SettingsSubScreenScaffold(title = s[S.DataAndBackup], onBack = onBack) {
        item {
            SettingsGroup(s[S.StorageAndLogs]) {
                SettingsSegmentedRow(
                    title = s[S.LogRetentionTitle],
                    currentBadge = s.format(S.LogRetentionDaysFormat, settings.logRetentionDays),
                    options = listOf(7, 14, 30, 90).map { days ->
                        days to s.format(S.LogRetentionDaysFormat, days)
                    },
                    selected = settings.logRetentionDays,
                    onSelect = { days -> backend.setLogRetentionDays(days) },
                )
                SettingsDivider(insetWithIcon = false)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            s[S.DbSizeLabel],
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            formatBytes(dbSizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                dbSizeBytes = backend.compactActivityDb()
                                toast(s[S.DbCompacted])
                            }
                        },
                    ) {
                        Text(s[S.CompactNow], maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        item {
            SettingsGroup(s[S.BackupAndSync]) {
                SettingsTile(
                    title = s[S.ExportRules],
                    subtitle = s[S.ExportRulesDesc],
                    icon = Icons.Rounded.Share,
                    iconAccent = MaterialTheme.colorScheme.primary,
                    onClick = { exportLauncher.launch("pocketgate-backup.json") },
                )
                SettingsDivider()
                SettingsTile(
                    title = s[S.ImportRules],
                    subtitle = s[S.ImportRulesDesc],
                    icon = Icons.Rounded.Refresh,
                    iconAccent = MaterialTheme.colorScheme.tertiary,
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                )
                SettingsDivider()
                SettingsTile(
                    title = s[S.LocalBackupSave],
                    subtitle = s[S.LocalBackupDesc],
                    icon = Icons.Rounded.CheckCircle,
                    iconAccent = MaterialTheme.colorScheme.secondary,
                    onClick = {
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { backend.saveLocalBackupToDownloads() }
                            res.onSuccess { path -> toast(s.format(S.LocalBackupSaved, path)) }
                                .onFailure { toast(s.format(S.ExportFailed, it.message.orEmpty())) }
                        }
                    },
                )
                SettingsDivider()
                SettingsTile(
                    title = s[S.LocalBackupRestore],
                    subtitle = s[S.LocalBackupDesc],
                    icon = Icons.Rounded.Edit,
                    iconAccent = MaterialTheme.colorScheme.secondary,
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
                )
                SettingsDivider()
                SettingsTile(
                    title = s[S.WebDavSync],
                    subtitle = s[S.WebDavSyncDesc],
                    icon = Icons.Rounded.Lock,
                    iconAccent = MaterialTheme.colorScheme.primary,
                    onClick = { showWebDavDialog = true },
                )
            }
        }

        item {
            SettingsGroup(s[S.Diagnostics]) {
                SettingsTile(
                    title = s[S.ExportDiagnostics],
                    subtitle = s[S.DiagnosticsDesc],
                    icon = Icons.Rounded.Build,
                    iconAccent = MaterialTheme.colorScheme.tertiary,
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
                )
            }
        }
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

    if (showImportDialog) {
        ImportDialog(
            preview = if (importMode == RuleImportMode.Merge) previewMerge else previewReplace,
            mode = importMode,
            onModeChange = { importMode = it },
            onDismiss = { showImportDialog = false },
            onApply = {
                val json = importJson
                val mode = importMode
                if (json != null) {
                    guard.run {
                        scope.launch {
                            runCatching { backend.applyImport(json, mode) }
                                .onSuccess { toast(s[S.ImportDone]) }
                                .onFailure { toast(s.format(S.ImportFailed, it.message.orEmpty())) }
                        }
                    }
                }
                showImportDialog = false
            },
        )
    }
}
