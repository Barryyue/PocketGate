package com.networkcontrol.android.kotlin.ui.overview

import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.ProtectionStatus
import com.networkcontrol.android.kotlin.core.model.PromptSnapshot
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.TrafficUsage
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.MetricCard
import com.networkcontrol.android.kotlin.ui.common.ScreenHeader
import com.networkcontrol.android.kotlin.ui.common.SectionCard
import com.networkcontrol.android.kotlin.ui.common.formatBytes
import com.networkcontrol.android.kotlin.ui.common.formatDateTime
import com.networkcontrol.android.kotlin.ui.common.protocolLabel
import com.networkcontrol.android.kotlin.ui.prompt.promptDisplayName
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OverviewScreen(backend: AppBackend) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    val runtime by backend.runtime.collectAsStateWithLifecycle()
    val protection by backend.protection.collectAsStateWithLifecycle()
    val tunnelState by backend.tunnelState.collectAsStateWithLifecycle()
    val prompts by backend.prompts.collectAsStateWithLifecycle()
    val profiles by backend.profiles.collectAsStateWithLifecycle()
    val settings by backend.settings.collectAsStateWithLifecycle()
    val quiet by backend.quietPeriod.collectAsStateWithLifecycle()
    val speed by backend.speed.collectAsStateWithLifecycle()
    val focus by backend.focus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var blockedToday by remember { mutableLongStateOf(0L) }
    var traffic by remember { mutableStateOf(TrafficUsage.Empty) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showBatchPrompts by remember { mutableStateOf(false) }
    var showFocusDialog by remember { mutableStateOf(false) }
    var confirmEndFocus by remember { mutableStateOf(false) }
    var privateDnsMode by remember { mutableStateOf("off") }

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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(s[S.Overview], s[S.OverviewSubtitle])
        }
        item {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(statusText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            s[S.ProtectionHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = protection,
                        onCheckedChange = { enabled ->
                            if (enabled) enableProtection() else backend.setProtection(false)
                        },
                    )
                }
                if (status == ProtectionStatus.NeedsConsent || status == ProtectionStatus.Revoked) {
                    Text(
                        s[S.VpnConsentRequired],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val error = backend.tunnelError.collectAsStateWithLifecycle().value
                if (!error.isNullOrBlank() && status == ProtectionStatus.Failed) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (privateDnsMode == "hostname") {
            item {
                SectionCard(s[S.PrivateDnsTitle]) {
                    Text(
                        s[S.PrivateDnsHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard(s[S.FocusMode]) {
                val active = focus
                if (active == null) {
                    Text(
                        s[S.FocusAllowedHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { showFocusDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(s[S.FocusStart]) }
                } else {
                    val remaining = (active.endsAt - now).coerceAtLeast(0)
                    Text(
                        s.format(S.FocusRemaining, formatRemaining(remaining)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (active.allowedPackages.isEmpty()) {
                            s[S.FocusNoAllowed]
                        } else {
                            "${s[S.FocusAllowedApps]}：${active.allowedPackages.size}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { backend.extendFocus(15) }) {
                            Text(s[S.FocusExtend])
                        }
                        OutlinedButton(
                            onClick = {
                                backend.createFocusProfile(
                                    allowedPackages = active.allowedPackages,
                                    copyFromCurrent = false,
                                )
                            },
                        ) { Text(s[S.SaveAsProfile]) }
                        TextButton(onClick = { confirmEndFocus = true }) {
                            Text(s[S.FocusStop])
                        }
                    }
                }
            }
        }
        item {
            SectionCard(s[S.Mode]) {
                val options = listOf(FilterMode.Ask, FilterMode.Whitelist, FilterMode.Blacklist)
                val labels = listOf(s[S.ModeAsk], s[S.ModeWhitelist], s[S.ModeBlacklist])
                val selected = options.indexOf(runtime.mode)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = selected == index,
                            onClick = { backend.setMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) {
                            Text(labels[index])
                        }
                    }
                }
                Text(
                    s[S.ModeHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        var expanded by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${s[S.Profile]}：${activeProfile?.name ?: "—"}")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.name) },
                                    onClick = {
                                        backend.activateProfile(profile.id)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                    if (runtime.mode == FilterMode.BlockAll) {
                        Button(onClick = { backend.setBlockAll(false) }) {
                            Text(s[S.Restore])
                        }
                    } else {
                        OutlinedButton(onClick = { backend.setBlockAll(true) }) {
                            Text(s[S.BlockAll])
                        }
                    }
                }
                if (runtime.mode == FilterMode.BlockAll) {
                    Text(
                        s[S.BlockAllHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item {
            SectionCard(s[S.DoNotDisturb]) {
                val until = runtime.doNotDisturbUntil
                val active = until != null && until > now
                if (active) {
                    Text(s[S.DoNotDisturbOn], style = MaterialTheme.typography.bodyMedium)
                    until?.let {
                        Text(
                            formatDateTime(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { backend.setDoNotDisturb(null) }) {
                        Text(s[S.Off])
                    }
                } else {
                    OutlinedButton(
                        onClick = { backend.setDoNotDisturb(System.currentTimeMillis() + 60 * 60 * 1000L) },
                    ) {
                        Text("${s[S.DoNotDisturb]} ${s[S.OneHour]}")
                    }
                }
            }
        }
        if (!backend.notificationsAvailable()) {
            item {
                SectionCard(s[S.Notifications]) {
                    Text(
                        s[S.NotificationPermissionMissing],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = { context.startActivity(backend.openNotificationSettings()) }) {
                        Text(s[S.OpenNotificationSettings])
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard(s[S.BlockedToday], blockedToday.toString(), Modifier.weight(1f))
                MetricCard(s[S.PendingPrompts], prompts.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard(
                    s[S.Rules],
                    backend.container.rules.ruleSet.value.count.toString(),
                    Modifier.weight(1f),
                )
                MetricCard(
                    s[S.TrafficToday],
                    formatBytes(traffic.bytesIn + traffic.bytesOut),
                    Modifier.weight(1f),
                    hint = if (backend.hasTrafficAccess()) null else s[S.NoTraffic],
                )
            }
        }
        item {
            SectionCard(s[S.TrafficToday]) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column {
                        Text(s[S.CurrentSpeed], style = MaterialTheme.typography.labelMedium)
                        Text(
                            "↓ ${formatBytes(speed.downloadBytesPerSecond)}/s · ↑ ${formatBytes(speed.uploadBytesPerSecond)}/s",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (!backend.hasTrafficAccess()) {
                    Text(
                        s[S.NoTraffic],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { context.startActivity(backend.trafficAccessIntent()) }) {
                        Text(s[S.GrantAccess])
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column {
                            Text(s[S.Download], style = MaterialTheme.typography.labelMedium)
                            Text(formatBytes(traffic.bytesIn), style = MaterialTheme.typography.titleMedium)
                        }
                        Column {
                            Text(s[S.Upload], style = MaterialTheme.typography.labelMedium)
                            Text(formatBytes(traffic.bytesOut), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    val simLabels = backend.subscriptions().associateBy { it.subscriptionId }
                    traffic.byNetwork.entries
                        .filter { it.value.first + it.value.second > 0 }
                        .sortedByDescending { it.value.first + it.value.second }
                        .take(4)
                        .forEach { (key, bytes) ->
                            val label = when (key.kind) {
                                com.networkcontrol.android.kotlin.core.model.NetworkKind.Wifi -> s[S.Wifi]
                                com.networkcontrol.android.kotlin.core.model.NetworkKind.Cellular ->
                                    key.subscriptionId?.let { simLabels[it]?.label } ?: s[S.Cellular]
                                com.networkcontrol.android.kotlin.core.model.NetworkKind.Wired -> s[S.Wired]
                                else -> s[S.Other]
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    formatBytes(bytes.first + bytes.second),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    val top = traffic.byPackage.entries
                        .sortedByDescending { it.value.first + it.value.second }
                        .take(3)
                    top.forEach { (packageName, usage) ->
                        val label = backend.container.appCatalog.find(packageName)?.label ?: packageName
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatBytes(usage.first + usage.second),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (prompts.isNotEmpty()) {
            item {
                SectionCard(s[S.PendingPrompts]) {
                    Text(
                        s.format(S.PromptFlows, prompts.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { showBatchPrompts = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(s[S.BatchPrompts])
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        item {
            OutlinedButton(
                onClick = { refreshKey++ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s[S.Refresh])
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
