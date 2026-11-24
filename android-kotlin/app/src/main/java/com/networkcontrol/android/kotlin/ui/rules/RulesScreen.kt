package com.networkcontrol.android.kotlin.ui.rules

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.networkcontrol.android.kotlin.core.model.PortRange
import com.networkcontrol.android.kotlin.ui.common.formatDateTime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.model.RemoteTarget
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleGroup
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.AppIcon
import com.networkcontrol.android.kotlin.ui.common.ScreenHeader
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import com.networkcontrol.android.kotlin.ui.theme.LocalAppVisualTokens
import kotlinx.coroutines.launch

@Composable
fun RulesScreen(
    backend: AppBackend,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    val s = rememberUiStrings()
    val guard = com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction.current
    val scope = rememberCoroutineScope()
    val ruleSet by backend.container.rules.ruleSet.collectAsStateWithLifecycle()
    val runtime by backend.runtime.collectAsStateWithLifecycle()
    val excludedPackages by backend.excludedPackages.collectAsStateWithLifecycle()
    val proxyBypassPackage by backend.proxyBypassPackage.collectAsStateWithLifecycle()
    val isProxyMode = backend.forwardingEnabled() &&
        backend.forwardingUpstream() == com.networkcontrol.android.kotlin.ForwardingUpstream.SOCKS5
    val activeProfileId = runtime.activeProfileId
    val sims = remember { runCatching { backend.subscriptions() }.getOrDefault(emptyList()) }
    var query by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<Rule?>(null) }
    val groups = remember(ruleSet.version, activeProfileId) { backend.ruleGroups() }
    val filteredGroups = remember(groups, query) {
        if (query.isBlank()) {
            groups
        } else {
            groups.mapNotNull { group ->
                val groupMatches = group.label.contains(query, ignoreCase = true) ||
                    group.packageName?.contains(query, ignoreCase = true) == true
                if (groupMatches) {
                    group
                } else {
                    val matchedRules = group.rules.filter { rule ->
                        remoteTargetLabel(rule.conditions)?.contains(query, ignoreCase = true) == true ||
                            rule.note?.contains(query, ignoreCase = true) == true
                    }
                    if (matchedRules.isNotEmpty()) group.copy(rules = matchedRules) else null
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 20.dp + com.networkcontrol.android.kotlin.ui.LocalBottomContentPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = s[S.Rules],
                subtitle = s[S.RulesSubtitle],
                trailing = {
                    FilledTonalButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(s[S.RuleAdd])
                    }
                },
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(s[S.SearchRulesHint]) },
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
        if (filteredGroups.isEmpty()) {
            item {
                Text(
                    if (query.isNotBlank()) s[S.NoMatchingRules] else s[S.NoRules],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(filteredGroups, key = { it.key }) { group ->
            val pkg = group.packageName
            RuleGroupCard(
                backend = backend,
                group = group,
                isExcluded = pkg != null && pkg in excludedPackages,
                isProxyBypass = pkg != null && isProxyMode && proxyBypassPackage == pkg,
                sims = sims,
                onEdit = { editingRule = it },
                onChanged = { },
                scope = scope,
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showCreateDialog || editingRule != null) {
        RuleEditorDialog(
            backend = backend,
            existing = editingRule,
            onDismiss = {
                showCreateDialog = false
                editingRule = null
            },
            onSave = { pkg, action, domain, port, networkKinds, note ->
                val target = editingRule
                showCreateDialog = false
                editingRule = null
                guard.run {
                    scope.launch {
                        backend.saveCustomRule(
                            existing = target,
                            packageName = pkg,
                            action = action,
                            domainPattern = domain,
                            remotePort = port,
                            networkKinds = networkKinds,
                            note = note,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun RuleGroupCard(
    backend: AppBackend,
    group: RuleGroup,
    isExcluded: Boolean,
    isProxyBypass: Boolean,
    sims: List<com.networkcontrol.android.kotlin.core.model.SimInfo>,
    onEdit: (Rule) -> Unit,
    onChanged: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val guard = com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction.current
    val s = rememberUiStrings()
    var pendingDelete by remember { mutableStateOf<Rule?>(null) }
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
    Card(
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = scheme.onSurface,
        ),
        shape = RoundedCornerShape(tokens.cardCornerRadius),
        border = border,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (group.packageName != null) {
                    AppIcon(group.packageName, Modifier.width(38.dp).height(38.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.packageName?.let { group.label } ?: s[S.GlobalRule],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    group.packageName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isExcluded || isProxyBypass) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(scheme.error.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            if (isExcluded) s[S.ExcludedBadge] else s[S.ProxyApp],
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.error,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(scheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text(
                        "${group.rules.size}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.primary,
                    )
                }
            }
            val bypassed = isExcluded || isProxyBypass
            if (bypassed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (isExcluded) s[S.ExcludedRulesInactiveHint] else s[S.ExcludedProxyAppHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    if (isExcluded && group.packageName != null) {
                        TextButton(
                            onClick = {
                                guard.run {
                                    backend.setAppExcluded(group.packageName, false)
                                    onChanged()
                                }
                            },
                        ) {
                            Text(s[S.ResumeFirewallRules])
                        }
                    }
                }
            }
            Column(
                modifier = if (bypassed) Modifier.alpha(0.5f) else Modifier,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                group.rules.forEach { rule ->
                    RuleRow(
                        rule = rule,
                        sims = sims,
                        onToggle = { enabled ->
                            guard.run {
                                scope.launch {
                                    backend.setRuleEnabled(rule.id, enabled)
                                    onChanged()
                                }
                            }
                        },
                        onEdit = { onEdit(rule) },
                        onDelete = { pendingDelete = rule },
                    )
                }
            }
        }
    }
    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(s[S.Delete]) },
            text = { Text(s[S.DeleteRuleConfirm]) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        guard.run {
                            scope.launch {
                                backend.deleteRule(rule.id)
                                onChanged()
                            }
                        }
                    },
                ) { Text(s[S.Delete], color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(s[S.Cancel]) }
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: Rule,
    sims: List<com.networkcontrol.android.kotlin.core.model.SimInfo>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = rememberUiStrings()
    val scheme = MaterialTheme.colorScheme
    val accent = if (rule.action == VerdictAction.Allow) scheme.primary else scheme.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(if (rule.enabled) accent else scheme.outlineVariant),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    actionLabel(rule.action, s),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Text(
                    "· ${networkScopeLabel(rule.conditions, sims, s)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            remoteTargetLabel(rule.conditions)?.let { remote ->
                Text(
                    remote,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                buildString {
                    append(lifetimeLabel(rule.lifetime, s))
                    append(" · ")
                    append(if (rule.profileId == null) s[S.GlobalRule] else s[S.ActiveProfile])
                    if (rule.hitCount > 0) {
                        append(" · ${rule.hitCount}×")
                    }
                    rule.lastHitAt?.let {
                        append(" · ")
                        append(s.format(S.RuleLastHit, formatDateTime(it)))
                    }
                    rule.expiresAt?.takeIf { rule.lifetime == RuleLifetime.Timed }?.let {
                        append(" · ")
                        append(s.format(S.RuleExpiresIn, formatDateTime(it)))
                    }
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = scheme.onSurfaceVariant,
            )
            rule.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        if (rule.source != RuleSource.Builtin) {
            TextButton(onClick = onEdit) { Text(s[S.Edit]) }
            TextButton(onClick = onDelete) { Text(s[S.Delete]) }
        }
    }
}

@Composable
private fun RuleEditorDialog(
    backend: AppBackend,
    existing: Rule?,
    onDismiss: () -> Unit,
    onSave: (
        packageName: String?,
        action: VerdictAction,
        domainPattern: String?,
        remotePort: PortRange?,
        networkKinds: Set<NetworkKind>,
        note: String?,
    ) -> Unit,
) {
    val s = rememberUiStrings()
    var action by remember(existing) { mutableStateOf(existing?.action ?: VerdictAction.Deny) }
    var globalScope by remember(existing) { mutableStateOf(existing?.app == null) }
    var packageName by remember(existing) {
        mutableStateOf(existing?.app?.pathPattern?.removePrefix("android-app://").orEmpty())
    }
    var domain by remember(existing) {
        mutableStateOf((existing?.conditions?.remote as? RemoteTarget.Domain)?.pattern.orEmpty())
    }
    var portText by remember(existing) {
        mutableStateOf(existing?.conditions?.remotePort?.toString().orEmpty())
    }
    var networkKinds by remember(existing) {
        mutableStateOf(existing?.conditions?.networkKinds ?: emptySet())
    }
    var note by remember(existing) { mutableStateOf(existing?.note.orEmpty()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val installedApps = remember { runCatching { backend.container.appCatalog.getApps().sortedBy { it.label.lowercase() } }.getOrDefault(emptyList()) }
    var appSearch by remember { mutableStateOf("") }

    fun parsePortRange(raw: String): PortRange? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val dash = trimmed.indexOf('-')
        return if (dash >= 0) {
            val from = trimmed.substring(0, dash).trim().toIntOrNull()
            val to = trimmed.substring(dash + 1).trim().toIntOrNull()
            if (from != null && to != null && from in 1..65535 && to in from..65535) PortRange(from, to) else null
        } else {
            val single = trimmed.toIntOrNull()
            if (single != null && single in 1..65535) PortRange(single, single) else null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) s[S.RuleAdd] else s[S.RuleEdit]) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = action == VerdictAction.Allow,
                        onClick = { action = VerdictAction.Allow },
                        label = { Text(s[S.Allow]) },
                    )
                    FilterChip(
                        selected = action == VerdictAction.Deny,
                        onClick = { action = VerdictAction.Deny },
                        label = { Text(s[S.Deny]) },
                    )
                }
                if (existing == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = globalScope,
                            onClick = { globalScope = true },
                            label = { Text(s[S.RuleScopeGlobal]) },
                        )
                        FilterChip(
                            selected = !globalScope,
                            onClick = { globalScope = false },
                            label = { Text(s[S.RuleScopeApp]) },
                        )
                    }
                    if (!globalScope) {
                        OutlinedTextField(
                            value = appSearch,
                            onValueChange = { appSearch = it },
                            label = { Text(s[S.SearchApps]) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val candidates = remember(installedApps, appSearch) {
                            installedApps.filter {
                                appSearch.isBlank() ||
                                    it.label.contains(appSearch, ignoreCase = true) ||
                                    it.packageName.contains(appSearch, ignoreCase = true)
                            }.take(6)
                        }
                        candidates.forEach { app ->
                            FilterChip(
                                selected = packageName == app.packageName,
                                onClick = { packageName = app.packageName },
                                label = { Text("${app.label} (${app.packageName})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it; errorText = null },
                    label = { Text(s[S.RuleDomainHint]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it; errorText = null },
                    label = { Text(s[S.RuleRemotePortHint]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = networkKinds.isEmpty(),
                        onClick = { networkKinds = emptySet() },
                        label = { Text(s[S.AnyNetwork]) },
                    )
                    FilterChip(
                        selected = NetworkKind.Wifi in networkKinds,
                        onClick = {
                            networkKinds = if (NetworkKind.Wifi in networkKinds) {
                                networkKinds - NetworkKind.Wifi
                            } else {
                                networkKinds + NetworkKind.Wifi
                            }
                        },
                        label = { Text(s[S.Wifi]) },
                    )
                    FilterChip(
                        selected = NetworkKind.Cellular in networkKinds,
                        onClick = {
                            networkKinds = if (NetworkKind.Cellular in networkKinds) {
                                networkKinds - NetworkKind.Cellular
                            } else {
                                networkKinds + NetworkKind.Cellular
                            }
                        },
                        label = { Text(s[S.Cellular]) },
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(s[S.RuleNoteHint]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorText?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedPort = if (portText.isBlank()) {
                        null
                    } else {
                        parsePortRange(portText) ?: run {
                            errorText = "Invalid port (1-65535)"
                            return@Button
                        }
                    }
                    if (existing == null && !globalScope && packageName.isBlank()) {
                        errorText = "Please select an app"
                        return@Button
                    }
                    if (existing == null && globalScope && domain.isBlank() && parsedPort == null) {
                        errorText = "Global rule requires domain or port"
                        return@Button
                    }
                    onSave(
                        if (globalScope) null else packageName,
                        action,
                        domain,
                        parsedPort,
                        networkKinds,
                        note,
                    )
                },
            ) { Text(s[S.Save]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

private fun actionLabel(
    action: VerdictAction,
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
): String = if (action == VerdictAction.Allow) s[S.Allow] else s[S.Deny]

private fun lifetimeLabel(
    lifetime: RuleLifetime,
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
): String = when (lifetime) {
    RuleLifetime.Permanent -> s[S.RuleLifetimePermanent]
    RuleLifetime.UntilAppExit, RuleLifetime.UntilProcessExit, RuleLifetime.UntilRestart -> s[S.RuleLifetimeUntilExit]
    RuleLifetime.Timed -> s[S.RuleLifetimeTimed]
    RuleLifetime.Once -> s[S.PromptOnce]
}

private fun remoteTargetLabel(conditions: RuleConditions): String? {
    val target = when (val remote = conditions.remote) {
        is RemoteTarget.Domain -> remote.pattern
        is RemoteTarget.Address -> remote.networks.joinToString(", ") { it.toString() }
        is RemoteTarget.Zone -> remote.zone.name
        null -> null
    }
    val port = conditions.remotePort?.toString()
    return when {
        target != null && port != null -> "$target:$port"
        target != null -> target
        port != null -> ":$port"
        else -> null
    }
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
