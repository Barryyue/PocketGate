package com.networkcontrol.android.kotlin.ui.logs

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.model.LogEntry
import com.networkcontrol.android.kotlin.core.model.VerdictReason
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.ScreenHeader
import com.networkcontrol.android.kotlin.ui.common.formatDateTime
import com.networkcontrol.android.kotlin.ui.common.protocolLabel
import com.networkcontrol.android.kotlin.ui.common.reasonLabel
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ReasonFilter { All, Rule, Mode, Prompt, Dnd }

@Composable
fun LogsScreen(backend: AppBackend) {
    val s = rememberUiStrings()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ReasonFilter.All) }
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<LogEntry?>(null) }

    LaunchedEffect(refreshKey) {
        entries = withContext(Dispatchers.IO) {
            runCatching { backend.getLogs(limit = 500) }.getOrDefault(emptyList())
        }
    }

    val filtered = entries.asSequence()
        .filter { entry ->
            query.isBlank() ||
                entry.appName?.contains(query, ignoreCase = true) == true ||
                entry.appPath?.contains(query, ignoreCase = true) == true ||
                entry.hostname?.contains(query, ignoreCase = true) == true ||
                entry.remoteIp.contains(query)
        }
        .filter { entry ->
            when (filter) {
                ReasonFilter.All -> true
                ReasonFilter.Rule -> entry.verdict.reason == VerdictReason.Rule
                ReasonFilter.Mode -> entry.verdict.reason == VerdictReason.Mode
                ReasonFilter.Prompt -> entry.verdict.reason == VerdictReason.Prompt
                ReasonFilter.Dnd -> entry.verdict.reason == VerdictReason.DoNotDisturb
            }
        }
        .toList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader(s[S.Logs], s[S.LogsSubtitle]) }
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
                FilterChip(filter == ReasonFilter.All, { filter = ReasonFilter.All }, { Text(s[S.FilterAll]) })
                FilterChip(filter == ReasonFilter.Rule, { filter = ReasonFilter.Rule }, { Text(s[S.ReasonRule]) })
                FilterChip(filter == ReasonFilter.Mode, { filter = ReasonFilter.Mode }, { Text(s[S.ReasonMode]) })
                FilterChip(filter == ReasonFilter.Prompt, { filter = ReasonFilter.Prompt }, { Text(s[S.ReasonPrompt]) })
                FilterChip(filter == ReasonFilter.Dnd, { filter = ReasonFilter.Dnd }, { Text(s[S.ReasonDnd]) })
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    s[S.NoLogs],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(filtered, key = { it.id }) { entry ->
            LogRow(entry, onClick = { selected = entry })
        }
        item {
            OutlinedButton(onClick = { refreshKey++ }, modifier = Modifier.fillMaxWidth()) {
                Text(s[S.Refresh])
            }
        }
    }

    selected?.let { entry ->
        LogDetailDialog(entry = entry, onDismiss = { selected = null })
    }
}

@Composable
private fun LogRow(entry: LogEntry, onClick: () -> Unit) {
    val s = rememberUiStrings()
    val target = entry.hostname ?: "${entry.remoteIp}:${entry.remotePort}"
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    entry.appName ?: entry.appPath ?: s[S.FilterUnset],
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "$target · ${protocolLabel(entry.protocol)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${reasonLabel(s, entry.verdict.reason)} · ${formatDateTime(entry.lastTs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text("×${entry.count}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    s[S.Deny],
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LogDetailDialog(entry: LogEntry, onDismiss: () -> Unit) {
    val s = rememberUiStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.LogDetail]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(s[S.Time], formatDateTime(entry.firstTs))
                DetailRow(s[S.App], entry.appName ?: entry.appPath ?: "—")
                DetailRow(s[S.Target], entry.hostname ?: "${entry.remoteIp}:${entry.remotePort}")
                DetailRow(s[S.Protocol], protocolLabel(entry.protocol))
                DetailRow(s[S.Reason], reasonLabel(s, entry.verdict.reason))
                DetailRow(s[S.Count], entry.count.toString())
                entry.local?.let { DetailRow("Local", "${it.address.hostAddress}:${it.port}") }
                entry.verdict.ruleId?.let { DetailRow(s[S.Rule], it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Close]) }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(
            "$label：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
