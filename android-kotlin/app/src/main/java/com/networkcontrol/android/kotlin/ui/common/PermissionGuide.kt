package com.networkcontrol.android.kotlin.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.res.painterResource
import com.networkcontrol.android.kotlin.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.WindowFocusBus
import com.networkcontrol.android.kotlin.core.model.FilterMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings
import com.networkcontrol.android.kotlin.ui.theme.LocalAppVisualTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 除 VPN 授權外的 5 項核心 / 進階權限即時快照。
 */
data class PermissionHealthSnapshot(
    val loaded: Boolean,
    val notificationsOk: Boolean,
    val batteryOk: Boolean,
    val appListOk: Boolean,
    val overlayOk: Boolean,
    val usageOk: Boolean,
) {
    val missingCount: Int
        get() = listOf(notificationsOk, batteryOk, appListOk, overlayOk, usageOk).count { !it }

    val readyCount: Int
        get() = 5 - missingCount

    fun missingSummary(s: UiStrings): String = buildList {
        if (!notificationsOk) add(s[S.PermNotifications])
        if (!batteryOk) add(s[S.PermBattery])
        if (!appListOk) add(s[S.PermAppList])
        if (!overlayOk) add(s[S.PermOverlay])
        if (!usageOk) add(s[S.PermUsage])
    }.joinToString(" · ")
}

@Composable
fun rememberPermissionHealth(backend: AppBackend, resumeKey: Int): PermissionHealthSnapshot {
    val focusTick by WindowFocusBus.ticks.collectAsStateWithLifecycle()
    val effectiveKey = resumeKey * 31 + focusTick
    val notifOk = remember(effectiveKey) { backend.notificationsAvailable() }
    val batteryOk = remember(effectiveKey) { backend.isIgnoringBatteryOptimizations() }
    val overlayOk = remember(effectiveKey) { backend.canDrawOverlays() }
    val usageOk = remember(effectiveKey) { backend.hasTrafficAccess() }
    // 讀取應用列表可能觸發 PackageManager 查詢，改在背景執行緒計算。
    val appListRestricted by produceState<Boolean?>(initialValue = null, key1 = effectiveKey) {
        value = withContext(Dispatchers.IO) {
            runCatching { backend.appListVisibility().restricted }.getOrDefault(false)
        }
    }
    return PermissionHealthSnapshot(
        loaded = appListRestricted != null,
        notificationsOk = notifOk,
        batteryOk = batteryOk,
        appListOk = appListRestricted != true,
        overlayOk = overlayOk,
        usageOk = usageOk,
    )
}

/**
 * 總覽頁精簡權限待辦橫幅（方案 C 的常駐輕量提示，配齊或關閉後自動消失）。
 */
@Composable
fun PermissionReminderBanner(
    health: PermissionHealthSnapshot,
    s: UiStrings,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalAppVisualTokens.current
    val accent = scheme.tertiary
    val cardShape = RoundedCornerShape(tokens.cardCornerRadius)
    val container = if (tokens.cardContainer != Color.Unspecified) {
        tokens.cardContainer
    } else {
        scheme.surfaceVariant.copy(alpha = 0.55f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = scheme.onSurface,
        ),
        border = BorderStroke(1.dp, accent.copy(alpha = if (tokens.isDarkCanvas) 0.45f else 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = if (tokens.isDarkCanvas) 0.22f else 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    s.format(S.PermBannerTitle, health.missingCount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    health.missingSummary(s),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(
                onClick = onConfigure,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(s[S.PermBannerAction], style = MaterialTheme.typography.labelMedium)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = s[S.Close],
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 一站式權限初始化引導彈窗（方案 C 的首次彈窗 + 隨時從總覽橫幅重開）。
 */
@Composable
fun PermissionGuideDialog(
    backend: AppBackend,
    health: PermissionHealthSnapshot,
    s: UiStrings,
    showIntroStep: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val allReady = health.missingCount == 0
    var step by remember(showIntroStep) { mutableIntStateOf(if (showIntroStep) 0 else 1) }
    val runtime by backend.runtime.collectAsStateWithLifecycle()
    val settings by backend.settings.collectAsStateWithLifecycle()
    var selectedMode by remember(runtime.mode) { mutableStateOf(runtime.mode) }
    var allowSystemApps by remember(settings.allowSystemAppsByDefault) { mutableStateOf(settings.allowSystemAppsByDefault) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (step == 0) {
                        s[S.OnboardingWelcomeTitle]
                    } else {
                        "${s[S.PermGuideTitle]} · ${health.readyCount}/5"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            if (step == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        s[S.OnboardingLocalVpnDesc],
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        s[S.OnboardingModePrompt],
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            FilterMode.Ask to s[S.ModeAsk],
                            FilterMode.Whitelist to s[S.ModeWhitelist],
                            FilterMode.Blacklist to s[S.ModeBlacklist],
                        ).forEach { (mode, label) ->
                            val active = selectedMode == mode
                            if (active) {
                                FilledTonalButton(
                                    onClick = { selectedMode = mode },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                            } else {
                                OutlinedButton(
                                    onClick = { selectedMode = mode },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            s[S.AllowSystemApps],
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.Switch(
                            checked = allowSystemApps,
                            onCheckedChange = { allowSystemApps = it },
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        s[S.PermGuideSubtitle],
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )

                    Text(
                        s[S.PermGuideCore],
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    PermissionGuideRow(
                        title = s[S.PermNotifications],
                        why = s[S.PermNotifWhy],
                        granted = health.notificationsOk,
                        grantLabel = s[S.GoToSettings],
                        onGrant = { runCatching { context.startActivity(backend.openNotificationSettings()) } },
                    )
                    PermissionGuideRow(
                        title = s[S.PermBattery],
                        why = s[S.PermBatteryWhy],
                        granted = health.batteryOk,
                        grantLabel = s[S.GoToSettings],
                        onGrant = { runCatching { context.startActivity(backend.openBatteryOptimizationSettings()) } },
                    )
                    PermissionGuideRow(
                        title = s[S.PermAppList],
                        why = s[S.PermAppListWhy],
                        granted = health.appListOk,
                        grantLabel = s[S.GoToSettings],
                        onGrant = { runCatching { context.startActivity(backend.openAppSettings()) } },
                    )

                    Text(
                        s[S.PermGuideEnhanced],
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    PermissionGuideRow(
                        title = s[S.PermOverlay],
                        why = s[S.PermOverlayWhy],
                        granted = health.overlayOk,
                        grantLabel = s[S.OverlayGrant],
                        onGrant = { runCatching { context.startActivity(backend.overlayPermissionIntent()) } },
                    )
                    PermissionGuideRow(
                        title = s[S.PermUsage],
                        why = s[S.PermUsageWhy],
                        granted = health.usageOk,
                        grantLabel = s[S.GrantAccess],
                        onGrant = { runCatching { context.startActivity(backend.trafficAccessIntent()) } },
                    )
                }
            }
        },
        confirmButton = {
            if (step == 0) {
                Button(
                    onClick = {
                        backend.setMode(selectedMode)
                        backend.setAllowSystemAppsByDefault(allowSystemApps)
                        step = 1
                    },
                ) {
                    Text(s[S.OnboardingNext])
                }
            } else if (allReady) {
                Button(onClick = onDismiss) {
                    Text(s[S.PermGuideDone])
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(s[S.PermGuideLater])
                }
            }
        },
        dismissButton = if (step == 0) {
            {
                TextButton(onClick = onDismiss) {
                    Text(s[S.PermGuideLater])
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun PermissionGuideRow(
    title: String,
    why: String,
    granted: Boolean,
    grantLabel: String,
    onGrant: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (granted) {
                    scheme.primary.copy(alpha = 0.08f)
                } else {
                    scheme.surfaceVariant.copy(alpha = 0.45f)
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (granted) scheme.primary else Color(0xFFF59E0B)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                why,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (granted) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(22.dp),
            )
        } else {
            OutlinedButton(
                onClick = onGrant,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(grantLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
