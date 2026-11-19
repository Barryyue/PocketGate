package com.networkcontrol.android.kotlin.ui.overview

import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.ForwardingUpstream
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.model.PromptSnapshot
import com.networkcontrol.android.kotlin.core.model.ProtectionStatus
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.TrafficUsage
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.ui.LocalBottomContentPadding
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.BentoTile
import com.networkcontrol.android.kotlin.ui.common.ScreenHeader
import com.networkcontrol.android.kotlin.ui.common.SectionCard
import com.networkcontrol.android.kotlin.ui.common.formatBytes
import com.networkcontrol.android.kotlin.ui.common.formatDateTime
import com.networkcontrol.android.kotlin.ui.common.protocolLabel
import com.networkcontrol.android.kotlin.ui.prompt.promptDisplayName
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import com.networkcontrol.android.kotlin.ui.theme.LocalAppVisualTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SPEED_HISTORY_POINTS = 36

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(backend: AppBackend) {
    val guard = com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction.current
    val s = rememberUiStrings()
    val context = LocalContext.current
    val runtime by backend.runtime.collectAsStateWithLifecycle()
    val protection by backend.protection.collectAsStateWithLifecycle()
    val tunnelState by backend.tunnelState.collectAsStateWithLifecycle()
    val tunnelError by backend.tunnelError.collectAsStateWithLifecycle()
    val forwardingActive by backend.forwardingActive.collectAsStateWithLifecycle()
    val prompts by backend.prompts.collectAsStateWithLifecycle()
    val profiles by backend.profiles.collectAsStateWithLifecycle()
    val settings by backend.settings.collectAsStateWithLifecycle()
    val quiet by backend.quietPeriod.collectAsStateWithLifecycle()
    val speed by backend.speed.collectAsStateWithLifecycle()
    val focus by backend.focus.collectAsStateWithLifecycle()
    val ruleSet by backend.container.rules.ruleSet.collectAsStateWithLifecycle()

    var blockedToday by remember { mutableLongStateOf(0L) }
    var traffic by remember { mutableStateOf(TrafficUsage.Empty) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showBatchPrompts by remember { mutableStateOf(false) }
    var showFocusDialog by remember { mutableStateOf(false) }
    var confirmEndFocus by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var privateDnsMode by remember { mutableStateOf("off") }

    // 滾動記錄最近 36 個取樣點的上下行速率，供平滑波形圖繪製。
    val downHistory = remember {
        mutableStateListOf<Long>().apply { repeat(SPEED_HISTORY_POINTS) { add(0L) } }
    }
    val upHistory = remember {
        mutableStateListOf<Long>().apply { repeat(SPEED_HISTORY_POINTS) { add(0L) } }
    }

    // 每秒持續滾動取樣上下行速率，即使速度為 0 波形也平穩流動。
    val latestSpeed by androidx.compose.runtime.rememberUpdatedState(speed)
    LaunchedEffect(Unit) {
        while (true) {
            if (downHistory.size >= SPEED_HISTORY_POINTS) downHistory.removeAt(0)
            downHistory.add(latestSpeed.downloadBytesPerSecond.coerceAtLeast(0L))
            if (upHistory.size >= SPEED_HISTORY_POINTS) upHistory.removeAt(0)
            upHistory.add(latestSpeed.uploadBytesPerSecond.coerceAtLeast(0L))
            kotlinx.coroutines.delay(1_000)
        }
    }

    LaunchedEffect(Unit) {
        privateDnsMode = withContext(Dispatchers.IO) {
            runCatching { backend.privateDnsMode() }.getOrDefault("off")
        }
    }

    LaunchedEffect(focus != null) {
        while (focus != null) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    LaunchedEffect(runtime.mode, protection, tunnelState, prompts.size, refreshKey) {
        blockedToday = withContext(Dispatchers.IO) { runCatching { backend.countBlockedToday() }.getOrDefault(0L) }
        traffic = withContext(Dispatchers.IO) { runCatching { backend.getTrafficToday() }.getOrDefault(TrafficUsage.Empty) }
    }

    val status = remember(protection, tunnelState) { backend.protectionStatus() }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        backend.setProtection(true)
    }

    val statusText = when (status) {
        ProtectionStatus.On -> s[S.ProtectionOn]
        ProtectionStatus.Off -> s[S.ProtectionOff]
        ProtectionStatus.NeedsConsent -> s[S.ProtectionNeedsConsent]
        ProtectionStatus.Starting -> s[S.ProtectionStarting]
        ProtectionStatus.Revoked -> s[S.ProtectionRevoked]
        ProtectionStatus.Failed -> s[S.ProtectionFailed]
    }
    val activeProfile = profiles.firstOrNull { it.id == runtime.activeProfileId } ?: profiles.firstOrNull()

    fun enableProtection() {
        val consent = backend.createConsentIntent()
        if (consent != null) consentLauncher.launch(consent) else backend.setProtection(true)
    }

    val tokens = LocalAppVisualTokens.current
    val scheme = MaterialTheme.colorScheme
    val bottomPadding = LocalBottomContentPadding.current

    val isBlockAll = runtime.mode == FilterMode.BlockAll
    val isActive = status == ProtectionStatus.On
    val heroAccent by animateColorAsState(
        targetValue = when {
            isBlockAll -> scheme.error
            isActive -> scheme.primary
            status == ProtectionStatus.Failed || status == ProtectionStatus.Revoked -> scheme.error
            else -> scheme.onSurfaceVariant
        },
        animationSpec = tween(260),
        label = "hero-accent",
    )

    val exitBadgeText = when {
        !isActive -> s[S.Off]
        forwardingActive && backend.isUsingExternalProxy() -> "${s[S.ForwardingMode]} · SOCKS5"
        forwardingActive -> "${s[S.ForwardingMode]} · ${s[S.UpstreamDirect]}"
        else -> activeProfile?.name ?: s[S.ProtectionOn]
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 18.dp,
                top = 18.dp,
                end = 18.dp,
                bottom = 20.dp + bottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. 簡潔頂欄：標題 + 配置文件名 + 輕量刷新圖示
            item {
                ScreenHeader(
                    title = s[S.Overview],
                    subtitle = activeProfile?.let { "${s[S.Profile]} · ${it.name}" } ?: s[S.OverviewSubtitle],
                    trailing = {
                        IconButton(onClick = { refreshKey++ }) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = s[S.Refresh],
                                tint = scheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            // 2. 旗艦防護狀態橫幅卡片（右側獨立開關防誤觸，滑動瀏覽不會誤關防護）
            item {
                val baseContainer = if (tokens.cardContainer != Color.Unspecified) {
                    tokens.cardContainer
                } else {
                    scheme.surfaceVariant.copy(alpha = 0.45f)
                }
                val heroBorderColor = when {
                    isActive || isBlockAll -> heroAccent.copy(alpha = if (tokens.isDarkCanvas) 0.40f else 0.30f)
                    tokens.cardBorder != Color.Transparent -> tokens.cardBorder
                    else -> Color.Transparent
                }
                val heroGradient = if (isActive || isBlockAll) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            heroAccent.copy(alpha = if (tokens.isDarkCanvas) 0.20f else 0.13f),
                            heroAccent.copy(alpha = 0.04f),
                            Color.Transparent,
                        ),
                    )
                } else {
                    null
                }
                val cardShape = RoundedCornerShape(tokens.cardCornerRadius)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = baseContainer,
                        contentColor = scheme.onSurface,
                    ),
                    shape = cardShape,
                    border = if (heroBorderColor != Color.Transparent) BorderStroke(1.dp, heroBorderColor) else null,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (heroGradient != null) Modifier.background(heroGradient) else Modifier)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(heroAccent.copy(alpha = if (tokens.isDarkCanvas) 0.22f else 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_shield),
                                    contentDescription = null,
                                    tint = heroAccent,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        statusText,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(heroAccent),
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    exitBadgeText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isActive) heroAccent else scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = protection,
                                onCheckedChange = { enabled ->
                                    if (enabled) enableProtection() else guard.run { backend.setProtection(false) }
                                },
                            )
                        }
                        if (status == ProtectionStatus.NeedsConsent || status == ProtectionStatus.Revoked) {
                            Text(
                                s[S.VpnConsentRequired],
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.error,
                            )
                        }
                        if (!tunnelError.isNullOrBlank() && (status == ProtectionStatus.Failed || status == ProtectionStatus.On)) {
                            Text(tunnelError!!, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                            if (backend.isUsingExternalProxy()) {
                                OutlinedButton(
                                    onClick = { backend.setForwardingUpstream(ForwardingUpstream.DIRECT) },
                                ) {
                                    Text(s[S.SwitchToDirect])
                                }
                            }
                        }
                    }
                }
            }

            // 3. 旗艦即時網速平滑波形圖（佔滿整行，FlClash 標誌性視覺焦點）
            item {
                SpeedChartCard(
                    title = s[S.CurrentSpeed],
                    downloadBytesPerSec = speed.downloadBytesPerSecond,
                    uploadBytesPerSec = speed.uploadBytesPerSecond,
                    downloadHistory = downHistory,
                    uploadHistory = upHistory,
                )
            }

            // 4. 緊湊模式與配置文件控制條
            item {
                SectionCard {
                    val options = listOf(FilterMode.Ask, FilterMode.Whitelist, FilterMode.Blacklist)
                    val labels = listOf(s[S.ModeAsk], s[S.ModeWhitelist], s[S.ModeBlacklist])
                    val selected = options.indexOf(runtime.mode)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = selected == index,
                                onClick = { guard.run { backend.setMode(mode) } },
                                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            ) {
                                Text(labels[index], maxLines = 1)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { showProfileSheet = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "${s[S.Profile]}：${activeProfile?.name ?: "—"}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isBlockAll) {
                            Button(onClick = { backend.setBlockAll(false) }) {
                                Text(s[S.Restore], maxLines = 1)
                            }
                        } else {
                            OutlinedButton(onClick = { backend.setBlockAll(true) }) {
                                Text(s[S.BlockAll], maxLines = 1)
                            }
                        }
                    }
                    if (isBlockAll) {
                        Text(
                            s[S.BlockAllHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                        )
                    }
                }
            }

            // 5. Bento 雙列網格 Row 1：今日攔截 + 待處理詢問
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BentoTile(
                        label = s[S.BlockedToday],
                        value = blockedToday.toString(),
                        icon = Icons.Rounded.Warning,
                        accent = if (blockedToday > 0) scheme.error else scheme.primary,
                        subtitle = if (isActive) s[S.ProtectionOn] else s[S.ProtectionOff],
                        modifier = Modifier.weight(1f),
                    )
                    BentoTile(
                        label = s[S.PendingPrompts],
                        value = prompts.size.toString(),
                        icon = Icons.Rounded.Notifications,
                        accent = if (prompts.isNotEmpty()) scheme.primary else scheme.onSurfaceVariant,
                        highlighted = prompts.isNotEmpty(),
                        subtitle = if (prompts.isNotEmpty()) s[S.BatchPrompts] else "—",
                        onClick = if (prompts.isNotEmpty()) ({ showBatchPrompts = true }) else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 6. Bento 雙列網格 Row 2：今日流量 + 生效規則數
            item {
                val hasTraffic = backend.hasTrafficAccess()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BentoTile(
                        label = s[S.TrafficToday],
                        value = formatBytes(traffic.bytesIn + traffic.bytesOut),
                        icon = Icons.Rounded.Info,
                        accent = scheme.tertiary,
                        subtitle = if (hasTraffic) {
                            "↓ ${formatBytes(traffic.bytesIn)} · ↑ ${formatBytes(traffic.bytesOut)}"
                        } else {
                            s[S.GrantAccess]
                        },
                        highlighted = !hasTraffic,
                        onClick = if (!hasTraffic) ({ context.startActivity(backend.trafficAccessIntent()) }) else null,
                        modifier = Modifier.weight(1f),
                    )
                    BentoTile(
                        label = s[S.Rules],
                        value = ruleSet.count.toString(),
                        icon = Icons.Rounded.Lock,
                        accent = scheme.primary,
                        subtitle = activeProfile?.name ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 7. Bento 雙列網格 Row 3：專注模式 + 勿擾模式（可一鍵點擊）
            item {
                val activeFocus = focus
                val dndUntil = runtime.doNotDisturbUntil
                val dndActive = dndUntil != null && dndUntil > now
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BentoTile(
                        label = s[S.FocusMode],
                        value = if (activeFocus != null) {
                            formatRemaining((activeFocus.endsAt - now).coerceAtLeast(0))
                        } else {
                            s[S.Off]
                        },
                        icon = Icons.Rounded.Star,
                        accent = if (activeFocus != null) scheme.primary else scheme.onSurfaceVariant,
                        highlighted = activeFocus != null,
                        subtitle = if (activeFocus != null) {
                            "${s[S.FocusAllowedApps]}：${activeFocus.allowedPackages.size}"
                        } else {
                            s[S.FocusStart]
                        },
                        onClick = {
                            if (activeFocus == null) showFocusDialog = true else confirmEndFocus = true
                        },
                        modifier = Modifier.weight(1f),
                    )
                    BentoTile(
                        label = s[S.DoNotDisturb],
                        value = if (dndActive) s[S.QuickOn] else s[S.Off],
                        icon = Icons.AutoMirrored.Rounded.List,
                        accent = if (dndActive) scheme.tertiary else scheme.onSurfaceVariant,
                        highlighted = dndActive,
                        subtitle = if (dndActive) {
                            formatDateTime(dndUntil!!)
                        } else {
                            s[S.OneHour]
                        },
                        onClick = {
                            if (dndActive) {
                                backend.setDoNotDisturb(null)
                            } else {
                                backend.setDoNotDisturb(System.currentTimeMillis() + 60 * 60 * 1000L)
                                if (!protection) enableProtection()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 專注進行中時展開快捷操作條（延長 15 分鐘 / 停止 / 存為配置）
            focus?.let { active ->
                item {
                    SectionCard(s[S.FocusMode]) {
                        val remaining = (active.endsAt - now).coerceAtLeast(0)
                        Text(
                            s.format(S.FocusRemaining, formatRemaining(remaining)),
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!protection) {
                            Text(
                                s[S.FocusNeedsProtection],
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.error,
                            )
                            OutlinedButton(
                                onClick = { enableProtection() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(s[S.EnableProtection]) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { backend.extendFocus(15) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(s[S.FocusExtend], maxLines = 1)
                            }
                            TextButton(
                                onClick = { confirmEndFocus = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(s[S.FocusStop], maxLines = 1)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                backend.createFocusProfile(
                                    allowedPackages = active.allowedPackages,
                                    copyFromCurrent = false,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(s[S.SaveAsProfile]) }
                    }
                }
            }

            // 系統狀態異常提醒（僅在必要時顯示，平時保持零干擾）
            if (privateDnsMode == "hostname") {
                item {
                    SectionCard(s[S.PrivateDnsTitle]) {
                        Text(
                            s[S.PrivateDnsHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!backend.notificationsAvailable()) {
                item {
                    SectionCard(s[S.Notifications]) {
                        Text(
                            s[S.NotificationPermissionMissing],
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                        )
                        OutlinedButton(onClick = { context.startActivity(backend.openNotificationSettings()) }) {
                            Text(s[S.OpenNotificationSettings])
                        }
                    }
                }
            }

            // 8. 今日流量明細（網路分類 + Top 3 應用，帶比例進度條）
            if (backend.hasTrafficAccess() && (traffic.bytesIn + traffic.bytesOut) > 0) {
                item {
                    SectionCard(s[S.TrafficToday]) {
                        val simLabels = backend.subscriptions().associateBy { it.subscriptionId }
                        val networks = traffic.byNetwork.entries
                            .filter { it.value.first + it.value.second > 0 }
                            .sortedByDescending { it.value.first + it.value.second }
                            .take(4)
                        networks.forEach { (key, bytes) ->
                            val label = when (key.kind) {
                                NetworkKind.Wifi -> s[S.Wifi]
                                NetworkKind.Cellular ->
                                    key.subscriptionId?.let { simLabels[it]?.label } ?: s[S.Cellular]
                                NetworkKind.Wired -> s[S.Wired]
                                else -> s[S.Other]
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    formatBytes(bytes.first + bytes.second),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                        val top = traffic.byPackage.entries
                            .sortedByDescending { it.value.first + it.value.second }
                            .take(3)
                        val maxAppBytes = top.firstOrNull()?.let { (it.value.first + it.value.second).coerceAtLeast(1L) } ?: 1L
                        top.forEach { (packageName, usage) ->
                            val total = usage.first + usage.second
                            val ratio = (total.toFloat() / maxAppBytes.toFloat()).coerceIn(0.06f, 1f)
                            val label = backend.container.appCatalog.find(packageName)?.label ?: packageName
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        formatBytes(total),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .background(scheme.onSurface.copy(alpha = 0.08f)),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(ratio)
                                            .height(4.dp)
                                            .clip(CircleShape)
                                            .background(scheme.primary.copy(alpha = 0.85f)),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            quiet?.let { summary ->
                item {
                    SectionCard(s[S.QuietSummary]) {
                        Text(
                            "${s[S.BlockedToday]}：${summary.blockedCount}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${formatDateTime(summary.startedAt)} – ${formatDateTime(summary.endedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        summary.topApps.forEach { (name, count) ->
                            Text("$name ×$count", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { backend.dismissQuietPeriod() }) {
                            Text(s[S.Close])
                        }
                    }
                }
            }
        }
    }

    // 配置文件底部抽屜（ModalBottomSheet 取代生硬下拉選單）
    if (showProfileSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showProfileSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    s[S.Profile],
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                profiles.forEach { profile ->
                    val isSelected = profile.id == activeProfile?.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                            )
                            .clickable {
                                backend.activateProfile(profile.id)
                                showProfileSheet = false
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) scheme.primary else scheme.onSurface,
                            )
                            val modeName = when (profile.mode) {
                                FilterMode.Ask -> s[S.ModeAsk]
                                FilterMode.Whitelist -> s[S.ModeWhitelist]
                                FilterMode.Blacklist -> s[S.ModeBlacklist]
                                FilterMode.BlockAll -> s[S.BlockAll]
                                FilterMode.Disabled -> s[S.Off]
                            }
                            Text(
                                modeName,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBatchPrompts) {
        BatchPromptDialog(
            prompts = prompts,
            timedMinutes = settings.promptTimedMinutes,
            onDismiss = { showBatchPrompts = false },
            onAnswer = { ids, action, lifetime ->
                backend.answerPrompts(
                    ids,
                    action,
                    lifetime,
                    if (lifetime == RuleLifetime.Timed) settings.promptTimedMinutes * 60 else null,
                )
                showBatchPrompts = false
            },
            onLater = { ids ->
                backend.dismissPrompts(ids)
                showBatchPrompts = false
            },
        )
    }

    if (showFocusDialog) {
        FocusStartDialog(
            backend = backend,
            onDismiss = { showFocusDialog = false },
            onStart = { minutes, allowed ->
                backend.startFocus(minutes, allowed)
                if (!protection) enableProtection()
                showFocusDialog = false
            },
        )
    }

    if (confirmEndFocus) {
        AlertDialog(
            onDismissRequest = { confirmEndFocus = false },
            title = { Text(s[S.FocusStop]) },
            text = { Text(s[S.FocusEndConfirm]) },
            confirmButton = {
                TextButton(onClick = {
                    backend.stopFocus()
                    confirmEndFocus = false
                }) { Text(s[S.FocusStop]) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEndFocus = false }) { Text(s[S.Cancel]) }
            },
        )
    }
}

@Composable
private fun FocusStartDialog(
    backend: AppBackend,
    onDismiss: () -> Unit,
    onStart: (Int, Set<String>) -> Unit,
) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    var minutes by remember { mutableIntStateOf(30) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<com.networkcontrol.android.kotlin.core.model.AppEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching { backend.appEntries() }.getOrDefault(emptyList())
        }
    }

    val filtered = apps.filter {
        query.isBlank() ||
            it.app.label.contains(query, ignoreCase = true) ||
            it.app.packageName.contains(query, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.FocusStart]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                minutes = (hour * 60 + minute).coerceIn(5, 24 * 60)
                            },
                            minutes / 60,
                            minutes % 60,
                            true,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${s[S.FocusDuration]}：${formatDurationMinutes(minutes)}")
                }
                Text(s[S.FocusAllowedApps], style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(s[S.SearchApps]) },
                    singleLine = true,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it.app.packageName }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = entry.app.packageName in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) {
                                        selected + entry.app.packageName
                                    } else {
                                        selected - entry.app.packageName
                                    }
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(entry.app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    entry.app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    s[S.FocusAllowedHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(minutes, selected) }) {
                Text(s[S.FocusStart])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

private fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun formatDurationMinutes(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

@Composable
private fun BatchPromptDialog(
    prompts: List<PromptSnapshot>,
    timedMinutes: Int,
    onDismiss: () -> Unit,
    onAnswer: (Set<String>, VerdictAction, RuleLifetime) -> Unit,
    onLater: (Set<String>) -> Unit,
) {
    val s = rememberUiStrings()
    var selected by remember(prompts.map { it.id }) {
        mutableStateOf(prompts.map { it.id }.toSet())
    }
    var lifetime by remember { mutableStateOf(RuleLifetime.Permanent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.BatchPrompts]) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selected = prompts.map { it.id }.toSet() }) {
                        Text(s[S.SelectAll])
                    }
                    TextButton(onClick = { selected = emptySet() }) {
                        Text(s[S.ClearSelection])
                    }
                }
                prompts.forEach { prompt ->
                    val name = prompt.app?.displayName
                        ?: prompt.app?.name
                        ?: promptDisplayName(prompt.executablePath)
                        ?: s[S.UnknownApp]
                    val first = prompt.flows.firstOrNull()
                    val target = first?.let {
                        it.domains.firstOrNull() ?: "${it.remote.address.hostAddress}:${it.remote.port}"
                    }.orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = prompt.id in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + prompt.id else selected - prompt.id
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "$target · ${first?.let { protocolLabel(it.protocol) }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(s[S.BatchPromptsHint], style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        lifetime == RuleLifetime.UntilAppExit,
                        { lifetime = RuleLifetime.UntilAppExit },
                        { Text(s[S.PromptOnce]) },
                    )
                    FilterChip(
                        lifetime == RuleLifetime.Permanent,
                        { lifetime = RuleLifetime.Permanent },
                        { Text(s[S.PromptPermanent]) },
                    )
                    FilterChip(
                        lifetime == RuleLifetime.Timed,
                        { lifetime = RuleLifetime.Timed },
                        { Text(s.format(S.PromptTimed, timedMinutes)) },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onLater(selected) }, enabled = selected.isNotEmpty()) {
                    Text(s[S.PromptLater])
                }
                OutlinedButton(
                    onClick = { onAnswer(selected, VerdictAction.Deny, lifetime) },
                    enabled = selected.isNotEmpty(),
                ) { Text(s[S.Deny]) }
                Button(
                    onClick = { onAnswer(selected, VerdictAction.Allow, lifetime) },
                    enabled = selected.isNotEmpty(),
                ) { Text(s[S.Allow]) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}
