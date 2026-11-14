package com.networkcontrol.android.kotlin.ui.apps

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.model.AppDecision
import com.networkcontrol.android.kotlin.core.model.AppEntry
import com.networkcontrol.android.kotlin.core.model.AppRoutingDecision
import com.networkcontrol.android.kotlin.core.model.TrafficUsage
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.AppIcon
import com.networkcontrol.android.kotlin.ui.common.ScreenHeader
import com.networkcontrol.android.kotlin.ui.common.formatBytes
import com.networkcontrol.android.kotlin.ui.common.formatDateTime
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
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
    var traffic by remember { mutableStateOf(TrafficUsage.Empty) }
    var lastSeen by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(plan, rules.version, refreshKey) {
        entries = withContext(Dispatchers.IO) { runCatching { backend.appEntries() }.getOrDefault(emptyList()) }
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

    val filtered = entries.asSequence()
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader(s[S.Apps], s[S.AppsSubtitle]) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(s[S.SearchApps]) },
                singleLine = true,
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
        if (filtered.isEmpty()) {
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
    traffic: Pair<Long, Long>?,
    lastSeen: Long?,
    onChanged: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val s = rememberUiStrings()
    val effectiveLabel = when (entry.effective) {
        AppRoutingDecision.Bypass -> s[S.Allow]
        AppRoutingDecision.Block -> s[S.Deny]
        AppRoutingDecision.BlockAndAsk -> s[S.ModeAsk]
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(entry.app.packageName, Modifier.width(44.dp).height(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        entry.app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.app.isSystem) {
                    Text(
                        s[S.FilterSystem],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecisionChip(
                    label = s[S.Allow],
                    selected = entry.decision == AppDecision.Allow,
                    onClick = {
                        scope.launch {
                            backend.setAppDecision(entry.app.packageName, AppDecision.Allow)
                            onChanged()
                        }
                    },
                )
                DecisionChip(
                    label = s[S.Deny],
                    selected = entry.decision == AppDecision.Deny,
                    onClick = {
                        scope.launch {
                            backend.setAppDecision(entry.app.packageName, AppDecision.Deny)
                            onChanged()
                        }
                    },
                )
                DecisionChip(
                    label = s[S.Unset],
                    selected = entry.decision == AppDecision.Unset,
                    onClick = {
                        scope.launch {
                            backend.setAppDecision(entry.app.packageName, AppDecision.Unset)
                            onChanged()
                        }
                    },
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
                        "${s[S.ReasonTimeout]}: ${formatDateTime(it)}",
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
}

@Composable
private fun DecisionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}
