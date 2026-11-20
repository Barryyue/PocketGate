package com.networkcontrol.android.kotlin.ui.apps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.model.AppDecision
import com.networkcontrol.android.kotlin.core.model.AppEntry
import com.networkcontrol.android.kotlin.core.model.AppRoutingDecision
import com.networkcontrol.android.kotlin.core.model.AppRoutingReason
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.model.LogEntry
import com.networkcontrol.android.kotlin.core.model.TrafficUsage
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.AppIcon
import com.networkcontrol.android.kotlin.ui.common.ScreenHeader
import com.networkcontrol.android.kotlin.ui.common.formatBytes
import com.networkcontrol.android.kotlin.ui.common.formatDateTime
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import com.networkcontrol.android.kotlin.ui.theme.LocalAppVisualTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppFilter { All, Allowed, Denied, Unset, System }

@Composable
fun AppsScreen(backend: AppBackend) {
    val s = rememberUiStrings()
    val scope = rememberCoroutineScope()
    val plan by backend.plan.collectAsStateWithLifecycle()
    val rules by backend.container.rules.ruleSet.collectAsStateWithLifecycle()
    val runtime by backend.runtime.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.All) }
    var entries by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var traffic by remember { mutableStateOf(TrafficUsage.Empty) }
    var lastSeen by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val sims = remember { runCatching { backend.subscriptions() }.getOrDefault(emptyList()) }

    LaunchedEffect(plan, rules.version, runtime.activeProfileId, refreshKey) {
        entries = withContext(Dispatchers.IO) { runCatching { backend.appEntries() }.getOrDefault(emptyList()) }
        loaded = true
        traffic = withContext(Dispatchers.IO) { runCatching { backend.getTrafficToday() }.getOrDefault(TrafficUsage.Empty) }
        lastSeen = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            runCatching {
                backend.container.activityStore.listAppActivity(now - 7L * 24 * 60 * 60 * 1000, now, 1000)
                    .mapNotNull { activity ->
                        activity.appPath
                            ?.removePrefix("android-app://")
                            ?.takeIf { it != activity.appPath }
                            ?.let { it to activity.lastSeen }
                    }
                    .toMap()
            }.getOrDefault(emptyMap())
        }
    }

    val filtered = remember(entries, query, filter) {
        entries.asSequence()
            .filter { app ->
                query.isBlank() ||
                    app.app.label.contains(query, ignoreCase = true) ||
                    app.app.packageName.contains(query, ignoreCase = true)
            }
            .filter { app ->
                when (filter) {
                    AppFilter.All -> true
                    AppFilter.Allowed -> app.decision == AppDecision.Allow
                    AppFilter.Denied -> app.decision == AppDecision.Deny
                    AppFilter.Unset -> app.decision == AppDecision.Unset
                    AppFilter.System -> app.app.isSystem
                }
            }
            .toList()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 20.dp + com.networkcontrol.android.kotlin.ui.LocalBottomContentPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader(s[S.Apps], s[S.AppsSubtitle]) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(s[S.SearchApps]) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = s[S.Close])
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(filter == AppFilter.All, { filter = AppFilter.All }, { Text(s[S.FilterAll]) })
                FilterChip(filter == AppFilter.Allowed, { filter = AppFilter.Allowed }, { Text(s[S.FilterAllowed]) })
                FilterChip(filter == AppFilter.Denied, { filter = AppFilter.Denied }, { Text(s[S.FilterDenied]) })
                FilterChip(filter == AppFilter.Unset, { filter = AppFilter.Unset }, { Text(s[S.FilterUnset]) })
                FilterChip(filter == AppFilter.System, { filter = AppFilter.System }, { Text(s[S.FilterSystem]) })
            }
        }
        if (!loaded) {
            item {
                Text(
                    s[S.Loading],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else if (filtered.isEmpty()) {
            item {
                Text(
                    s[S.NoApps],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(filtered, key = { it.app.packageName }) { entry ->
            AppRow(
                backend = backend,
                entry = entry,
                rulesVersion = rules.version,
                activeProfileId = runtime.activeProfileId,
                sims = sims,
                traffic = traffic.byPackage[entry.app.packageName],
                lastSeen = lastSeen[entry.app.packageName],
                onChanged = { refreshKey++ },
                scope = scope,
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun AppRow(
    backend: AppBackend,
    entry: AppEntry,
    rulesVersion: Long,
    activeProfileId: String,
    sims: List<com.networkcontrol.android.kotlin.core.model.SimInfo>,
    traffic: Pair<Long, Long>?,
    lastSeen: Long?,
    onChanged: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val guard = com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction.current
    val s = rememberUiStrings()
    var showNetworkDialog by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    val appRules = remember(entry.app.packageName, rulesVersion, activeProfileId) {
        backend.appRules(entry.app.packageName)
    }
    val scopedRules = remember(appRules) { appRules.filter { it.conditions.hasNetworkScope } }
    val effectiveLabel = when (entry.effective) {
        AppRoutingDecision.Bypass -> s[S.Allow]
        AppRoutingDecision.Block -> s[S.Deny]
        AppRoutingDecision.BlockAndAsk -> s[S.ModeAsk]
    }

    val tokens = LocalAppVisualTokens.current
    val scheme = MaterialTheme.colorScheme
    val container = if (tokens.cardContainer != Color.Unspecified) {
        tokens.cardContainer
    } else {
        scheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val border = if (tokens.cardBorder != Color.Transparent) {
        BorderStroke(1.dp, tokens.cardBorder)
    } else {
        null
    }
    val statusDotColor = when (entry.effective) {
        AppRoutingDecision.Bypass -> scheme.primary
        AppRoutingDecision.Block -> scheme.error
        AppRoutingDecision.BlockAndAsk -> scheme.onSurfaceVariant
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = scheme.onSurface,
        ),
        shape = RoundedCornerShape(tokens.metricCornerRadius),
        border = border,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(entry.app.packageName, Modifier.width(44.dp).height(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            entry.app.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusDotColor),
                        )
                    }
                    Text(
                        entry.app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.app.isSystem) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(scheme.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            s[S.FilterSystem],
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecisionChip(
                    label = s[S.Allow],
                    selected = entry.decision == AppDecision.Allow,
                    onClick = {
                        guard.run { scope.launch {
                            backend.setAppDecision(
                                entry.app.packageName,
                                AppDecision.Allow,
                                replaceAllScopes = false,
                            )
                            onChanged()
                        } }
                    },
                )
                DecisionChip(
                    label = s[S.Deny],
                    selected = entry.decision == AppDecision.Deny,
                    onClick = {
                        guard.run { scope.launch {
                            backend.setAppDecision(
                                entry.app.packageName,
                                AppDecision.Deny,
                                replaceAllScopes = false,
                            )
                            onChanged()
                        } }
                    },
                )
                DecisionChip(
                    label = s[S.Unset],
                    selected = entry.decision == AppDecision.Unset,
                    onClick = {
                        guard.run { scope.launch {
                            backend.setAppDecision(
                                entry.app.packageName,
                                AppDecision.Unset,
                                replaceAllScopes = false,
                            )
                            onChanged()
                        } }
                    },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showNetworkDialog = true }) {
                    Text(s[S.NetworkConditions])
                }
                TextButton(onClick = { showDetail = true }) {
                    Text(s[S.Details])
                }
                if (scopedRules.isNotEmpty()) {
                    Text(
                        "${s[S.NetworkRules]}：${scopedRules.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            scopedRules.forEach { rule ->
                Text(
                    "${networkScopeLabel(rule.conditions, sims, s)} → ${actionLabel(rule.action, s)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${s[S.Effective]}：$effectiveLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (lastSeen != null) {
                    Text(
                        "${s[S.LastSeen]}：${formatDateTime(lastSeen)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            traffic?.let {
                Text(
                    "${s[S.TrafficToday]}：${formatBytes(it.first + it.second)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.isTemporary) {
                entry.expiresAt?.let {
                    Text(
                        "${s[S.ExpiresAt]}: ${formatDateTime(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (entry.isTampered) {
                Text(s[S.Tampered], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showNetworkDialog) {
        NetworkRuleDialog(
            title = entry.app.label,
            rules = appRules,
            initialDecision = entry.decision,
            initialKind = backend.container.network.current.kind
                .takeIf { it == NetworkKind.Wifi || it == NetworkKind.Cellular || it == NetworkKind.Wired },
            sims = sims,
            onDismiss = { showNetworkDialog = false },
            onSave = { decision, kinds, subscriptions ->
                guard.run { scope.launch {
                    backend.setAppDecision(
                        entry.app.packageName,
                        decision,
                        networkKinds = kinds,
                        subscriptionIds = subscriptions,
                        replaceAllScopes = false,
                    )
                    showNetworkDialog = false
                    onChanged()
                } }
            },
        )
    }

    if (showDetail) {
        AppDetailDialog(
            backend = backend,
            entry = entry,
            rules = appRules,
            traffic = traffic,
            sims = sims,
            onDismiss = { showDetail = false },
        )
    }
}

@Composable
private fun AppDetailDialog(
    backend: AppBackend,
    entry: AppEntry,
    rules: List<Rule>,
    traffic: Pair<Long, Long>?,
    sims: List<com.networkcontrol.android.kotlin.core.model.SimInfo>,
    onDismiss: () -> Unit,
) {
    val guard = com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction.current
    val s = rememberUiStrings()
    var recent by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val settings by backend.settings.collectAsStateWithLifecycle()

    LaunchedEffect(entry.app.packageName) {
        recent = withContext(Dispatchers.IO) {
            runCatching { backend.appRecentLogs(entry.app.packageName) }.getOrDefault(emptyList())
        }
        loaded = true
    }

    val effectiveLabel = when (entry.effective) {
        AppRoutingDecision.Bypass -> s[S.Allow]
        AppRoutingDecision.Block -> s[S.Deny]
        AppRoutingDecision.BlockAndAsk -> s[S.ModeAsk]
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(entry.app.packageName, Modifier.width(40.dp).height(40.dp))
                Spacer(Modifier.width(10.dp))
                Text(entry.app.label)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(entry.app.packageName, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${s[S.Uid]}：${entry.app.uid}" +
                        if (entry.app.isSystem) " · ${s[S.FilterSystem]}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.app.signerSha256?.let {
                    Text(
                        "${s[S.Signer]}：${it.take(16)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (it !in settings.trustedSigners) {
                        TextButton(onClick = { guard.run { backend.trustPublisher(it) } }) {
                            Text(s[S.TrustPublisher])
                        }
                    } else {
                        Text(
                            s[S.Trusted],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "${s[S.Effective]}：$effectiveLabel · ${s[S.Reason]}：${routingReasonLabel(entry.reason, s)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                traffic?.let {
                    Text(
                        "${s[S.TrafficToday]}：${formatBytes(it.first + it.second)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("${s[S.Rules]}：${rules.size}", style = MaterialTheme.typography.titleSmall)
                if (rules.isEmpty()) {
                    Text(
                        s[S.ModeAsk],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rules.forEach { rule ->
                    Text(
                        "${networkScopeLabel(rule.conditions, sims, s)} → ${actionLabel(rule.action, s)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(s[S.RecentActivity], style = MaterialTheme.typography.titleSmall)
                if (loaded && recent.isEmpty()) {
                    Text(
                        s[S.NoRecentActivity],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                recent.groupBy { it.hostname ?: "${it.remoteIp}:${it.remotePort}" }
                    .map { (target, list) ->
                        Triple(target, list.sumOf { it.count }, list.maxOf { it.lastTs })
                    }
                    .sortedByDescending { it.second }
                    .take(10)
                    .forEach { (target, count, lastTs) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                target,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "$count× · ${formatDateTime(lastTs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Close]) }
        },
    )
}

@Composable
private fun NetworkRuleDialog(
    title: String,
    rules: List<Rule>,
    initialDecision: AppDecision,
    initialKind: NetworkKind?,
    sims: List<com.networkcontrol.android.kotlin.core.model.SimInfo>,
    onDismiss: () -> Unit,
    onSave: (AppDecision, Set<NetworkKind>, Set<Int>) -> Unit,
) {
    val s = rememberUiStrings()
    var kind by remember { mutableStateOf(initialKind) }
    var subscriptionId by remember { mutableStateOf<Int?>(null) }
    var decision by remember {
        mutableStateOf(
            when (initialDecision) {
                AppDecision.Allow -> AppDecision.Allow
                AppDecision.Deny -> AppDecision.Deny
                AppDecision.Unset -> AppDecision.Allow
            },
        )
    }

    fun matchingRule(): Rule? {
        val kinds = kind?.let { setOf(it) } ?: emptySet()
        val subscriptions = if (kind == NetworkKind.Cellular) {
            subscriptionId?.let { setOf(it) } ?: emptySet()
        } else {
            emptySet()
        }
        return rules.firstOrNull {
            it.conditions.networkKinds == kinds && it.conditions.subscriptionIds == subscriptions
        }
    }

    LaunchedEffect(kind, subscriptionId) {
        val existing = matchingRule()
        decision = when (existing?.action) {
            VerdictAction.Allow -> AppDecision.Allow
            VerdictAction.Deny -> AppDecision.Deny
            null -> AppDecision.Unset
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(s[S.NetworkConditions], style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(kind == null, { kind = null; subscriptionId = null }, { Text(s[S.AnyNetwork]) })
                    FilterChip(kind == NetworkKind.Wifi, { kind = NetworkKind.Wifi; subscriptionId = null }, { Text(s[S.Wifi]) })
                    FilterChip(
                        kind == NetworkKind.Cellular,
                        { kind = NetworkKind.Cellular },
                        { Text(s[S.Cellular]) },
                    )
                    FilterChip(kind == NetworkKind.Wired, { kind = NetworkKind.Wired; subscriptionId = null }, { Text(s[S.Wired]) })
                }
                if (kind == NetworkKind.Cellular) {
                    Text(s[S.SimCard], style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            subscriptionId == null,
                            { subscriptionId = null },
                            { Text(s[S.AllSims]) },
                        )
                        sims.forEach { sim ->
                            FilterChip(
                                subscriptionId == sim.subscriptionId,
                                { subscriptionId = sim.subscriptionId },
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
                    }
                }
                Text(s[S.RuleAction], style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(decision == AppDecision.Allow, { decision = AppDecision.Allow }, { Text(s[S.Allow]) })
                    FilterChip(decision == AppDecision.Deny, { decision = AppDecision.Deny }, { Text(s[S.Deny]) })
                    FilterChip(decision == AppDecision.Unset, { decision = AppDecision.Unset }, { Text(s[S.Unset]) })
                }
                Text(
                    s[S.NetworkRuleHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        decision,
                        kind?.let { setOf(it) } ?: emptySet(),
                        if (kind == NetworkKind.Cellular) {
                            subscriptionId?.let { setOf(it) } ?: emptySet()
                        } else {
                            emptySet()
                        },
                    )
                },
            ) { Text(s[S.Save]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

@Composable
private fun DecisionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

private fun networkScopeLabel(
    conditions: RuleConditions,
    sims: List<com.networkcontrol.android.kotlin.core.model.SimInfo>,
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
): String {
    val parts = mutableListOf<String>()
    if (conditions.networkKinds.isNotEmpty()) {
        parts += conditions.networkKinds.sortedBy { it.code }.joinToString("/") { kind ->
            when (kind) {
                NetworkKind.Wifi -> s[S.Wifi]
                NetworkKind.Cellular -> s[S.Cellular]
                NetworkKind.Wired -> s[S.Wired]
                NetworkKind.Vpn -> "VPN"
                NetworkKind.Other -> s[S.Other]
                NetworkKind.None -> s[S.AnyNetwork]
            }
        }
    }
    if (conditions.subscriptionIds.isNotEmpty()) {
        parts += conditions.subscriptionIds.sorted().joinToString("/") { id ->
            sims.firstOrNull { it.subscriptionId == id }?.label ?: "${s[S.SimCard]} $id"
        }
    }
    if (conditions.simSlots.isNotEmpty()) {
        parts += conditions.simSlots.sorted().joinToString("/") { "${s[S.SimCard]} ${it + 1}" }
    }
    return parts.ifEmpty { listOf(s[S.AnyNetwork]) }.joinToString(" · ")
}

private fun actionLabel(
    action: VerdictAction,
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
): String = if (action == VerdictAction.Allow) s[S.Allow] else s[S.Deny]

private fun routingReasonLabel(
    reason: AppRoutingReason,
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
): String = when (reason) {
    AppRoutingReason.Rule -> s[S.ReasonRule]
    AppRoutingReason.Mode -> s[S.ReasonMode]
    AppRoutingReason.Self -> "Self"
    AppRoutingReason.SystemUid -> "System UID"
    AppRoutingReason.Trusted -> s[S.Trusted]
    AppRoutingReason.Focus -> s[S.FocusMode]
    AppRoutingReason.LockScreen -> s[S.BlockOnLock]
}

