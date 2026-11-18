package com.networkcontrol.android.kotlin.ui.logs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.model.LogEntry
import com.networkcontrol.android.kotlin.core.model.VerdictReason
import com.networkcontrol.android.kotlin.data.transfer.LogCsv
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.ScreenHeader
import com.networkcontrol.android.kotlin.ui.common.formatDateTime
import com.networkcontrol.android.kotlin.ui.common.protocolLabel
import com.networkcontrol.android.kotlin.ui.common.reasonLabel
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private enum class ReasonFilter { All, Rule, Mode, Prompt, Dnd, Blocklist }

@Composable
fun LogsScreen(backend: AppBackend) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ReasonFilter.All) }
    // 逐連線模式下也會記錄已允許的連線；預設顯示已攔截的。
    var showAllowed by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<LogEntry?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<List<LogEntry>?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val data = pendingExport
        pendingExport = null
        if (uri == null || data == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(LogCsv.encode(data).toByteArray(Charsets.UTF_8))
                    } ?: error("無法開啟輸出檔案")
                }
            }
            val message = if (result.isSuccess) {
                s[S.ExportDone]
            } else {
                s.format(S.ExportFailed, result.exceptionOrNull()?.message.orEmpty())
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(refreshKey, showAllowed) {
        entries = withContext(Dispatchers.IO) {
            val action = if (showAllowed) {
                com.networkcontrol.android.kotlin.core.model.VerdictAction.Allow
            } else {
                com.networkcontrol.android.kotlin.core.model.VerdictAction.Deny
            }
            runCatching { backend.getLogs(limit = 500, action = action) }.getOrDefault(emptyList())
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
                ReasonFilter.Blocklist -> entry.verdict.reason == VerdictReason.Blocklist
            }
        }
        .toList()

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!showAllowed, { showAllowed = false }, { Text(s[S.LogsShowBlocked]) })
                FilterChip(showAllowed, { showAllowed = true }, { Text(s[S.LogsShowAllowed]) })
            }
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
                FilterChip(filter == ReasonFilter.Blocklist, { filter = ReasonFilter.Blocklist }, { Text(s[S.ReasonBlocklist]) })
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
            LogRow(entry, tracker = backend.trackerOf(entry.hostname), onClick = { selected = entry })
        }
        item {
            OutlinedButton(onClick = { showExportDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(s[S.ExportCsv])
            }
        }
        item {
            OutlinedButton(onClick = { refreshKey++ }, modifier = Modifier.fillMaxWidth()) {
                Text(s[S.Refresh])
            }
        }
    }

    selected?.let { entry ->
        LogDetailDialog(
            entry = entry,
            tracker = backend.trackerOf(entry.hostname),
            listName = { id -> backend.blocklistName(id) },
            onAllowDomain = { host ->
                scope.launch {
                    runCatching { backend.allowDomain(host) }
                        .onSuccess { Toast.makeText(context, s.format(S.AllowDomainDone, host), Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, it.message.orEmpty(), Toast.LENGTH_SHORT).show() }
                }
                selected = null
            },
            onDismiss = { selected = null },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(s[S.ExportCsv]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            pendingExport = filtered
                            showExportDialog = false
                            createDocument.launch(exportFileName())
                        },
                    ) { Text(s[S.ExportFiltered]) }
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            scope.launch {
                                pendingExport = withContext(Dispatchers.IO) {
                                    runCatching { backend.getAllLogsForExport() }
                                        .getOrDefault(emptyList())
                                }
                                createDocument.launch(exportFileName())
                            }
                        },
                    ) { Text(s[S.ExportAll]) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) { Text(s[S.Cancel]) }
            },
        )
    }
}

private val exportNameTime = DateTimeFormatter.ofPattern("HHmm")

private fun exportFileName(): String {
    val date = LocalDate.now()
    val time = LocalTime.now().format(exportNameTime)
    return "networkcontrol-logs-$date-$time.csv"
}

@Composable
private fun LogRow(
    entry: LogEntry,
    tracker: com.networkcontrol.android.kotlin.engine.blocklist.TrackerInfo? = null,
    onClick: () -> Unit,
) {
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
                if (tracker != null) {
                    Text(
                        "${s[S.TrackerLabel]}：${tracker.company} · ${com.networkcontrol.android.kotlin.ui.common.trackerCategoryLabel(s, tracker.category)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text("×${entry.count}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                val allowed = entry.verdict.action == com.networkcontrol.android.kotlin.core.model.VerdictAction.Allow
                Text(
                    if (allowed) s[S.Allow] else s[S.Deny],
                    style = MaterialTheme.typography.labelSmall,
                    color = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LogDetailDialog(
    entry: LogEntry,
    tracker: com.networkcontrol.android.kotlin.engine.blocklist.TrackerInfo?,
    listName: (String) -> String?,
    onAllowDomain: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                tracker?.let {
                    DetailRow(s[S.TrackerLabel], "${it.company} · ${com.networkcontrol.android.kotlin.ui.common.trackerCategoryLabel(s, it.category)}")
                }
                DetailRow(s[S.Count], entry.count.toString())
                entry.local?.let { DetailRow("Local", "${it.address.hostAddress}:${it.port}") }
                entry.verdict.ruleId?.let { id ->
                    if (id.startsWith("blocklist:")) {
                        val listId = id.removePrefix("blocklist:")
                        val name = when {
                            listId == "dns-bypass" -> s[S.DnsBypassListName]
                            listId.startsWith("tracker:") ->
                                "Disconnect · " + com.networkcontrol.android.kotlin.ui.common.trackerCategoryLabel(s, listId.removePrefix("tracker:"))
                            else -> listName(listId) ?: listId
                        }
                        DetailRow(s[S.ReasonBlocklist], name)
                    } else {
                        DetailRow(s[S.Rule], id)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Close]) }
        },
        dismissButton = {
            val host = entry.hostname
            if (entry.verdict.reason == VerdictReason.Blocklist && host != null) {
                TextButton(onClick = { onAllowDomain(host) }) { Text(s[S.AllowDomain]) }
            }
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
