package com.networkcontrol.android.kotlin.ui.logs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.networkcontrol.android.kotlin.ui.theme.LocalAppVisualTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private enum class ReasonFilter { All, Rule, Mode, Prompt, Dnd, Blocklist }
private enum class LogsTab { Blocked, Allowed, Live }

@Composable
fun LogsScreen(
    backend: AppBackend,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    val guard = com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction.current
    val s = rememberUiStrings()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ReasonFilter.All) }
    var activeTab by remember { mutableStateOf(LogsTab.Blocked) }
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var liveConnections by remember { mutableStateOf<List<AppBackend.LiveConnection>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<LogEntry?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<List<LogEntry>?>(null) }
    val forwardingActive by backend.forwardingActive.collectAsStateWithLifecycle()

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
                s[S.ExportLogsDone]
            } else {
                s.format(S.ExportFailed, result.exceptionOrNull()?.message.orEmpty())
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(refreshKey, activeTab) {
        if (activeTab == LogsTab.Live) {
            while (true) {
                liveConnections = withContext(Dispatchers.IO) {
                    runCatching { backend.liveConnections() }.getOrDefault(emptyList())
                }
                kotlinx.coroutines.delay(2_000)
            }
        } else {
            loaded = false
            entries = withContext(Dispatchers.IO) {
                val action = if (activeTab == LogsTab.Allowed) {
                    com.networkcontrol.android.kotlin.core.model.VerdictAction.Allow
                } else {
                    com.networkcontrol.android.kotlin.core.model.VerdictAction.Deny
                }
                runCatching { backend.getLogs(limit = 500, action = action) }.getOrDefault(emptyList())
            }
            loaded = true
        }
    }

    val filteredLive = remember(liveConnections, query) {
        liveConnections.filter { conn ->
            query.isBlank() ||
                conn.appLabel.contains(query, ignoreCase = true) ||
                conn.packageName?.contains(query, ignoreCase = true) == true ||
                conn.hostname?.contains(query, ignoreCase = true) == true ||
                conn.remote.address.hostAddress.orEmpty().contains(query)
        }
    }

    val filtered = remember(entries, query, filter) {
        entries.asSequence()
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
                title = s[S.Logs],
                subtitle = s[S.LogsSubtitle],
                trailing = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = s[S.Refresh],
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(s[S.SearchLogsHint]) },
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(activeTab == LogsTab.Blocked, { activeTab = LogsTab.Blocked }, { Text(s[S.LogsShowBlocked]) })
                FilterChip(activeTab == LogsTab.Allowed, { activeTab = LogsTab.Allowed }, { Text(s[S.LogsShowAllowed]) })
                FilterChip(
                    activeTab == LogsTab.Live,
                    { activeTab = LogsTab.Live },
                    { Text(if (liveConnections.isNotEmpty() && activeTab == LogsTab.Live) "${s[S.LogsShowLive]} (${liveConnections.size})" else s[S.LogsShowLive]) },
                )
            }
        }
        if (activeTab == LogsTab.Live) {
            if (!forwardingActive) {
                item {
                    Text(
                        s[S.LiveNeedsForwarding],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else if (filteredLive.isEmpty()) {
                item {
                    Text(
                        s[S.NoLiveConnections],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(
                    filteredLive,
                    key = { "${it.local.port}-${it.remote.address.hostAddress}-${it.remote.port}" },
                ) { conn ->
                    LiveConnectionRow(
                        conn = conn,
                        onCut = {
                            guard.run {
                                val cut = backend.cutLiveConnection(conn.local, conn.remote)
                                if (cut) {
                                    val target = conn.hostname ?: "${conn.remote.address.hostAddress}:${conn.remote.port}"
                                    Toast.makeText(context, s.format(S.ConnectionCutDone, target), Toast.LENGTH_SHORT).show()
                                    liveConnections = liveConnections.filterNot { it.local == conn.local && it.remote == conn.remote }
                                } else {
                                    Toast.makeText(context, s[S.ConnectionCutFailed], Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                }
            }
        } else {
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
                val emptyText = when {
                    query.isNotBlank() || filter != ReasonFilter.All -> s[S.NoMatchingLogs]
                    activeTab == LogsTab.Allowed -> s[S.NoAllowedLogs]
                    else -> s[S.NoLogs]
                }
                item {
                    Text(
                        emptyText,
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
        }
    }

    selected?.let { entry ->
        LogDetailDialog(
            entry = entry,
            tracker = backend.trackerOf(entry.hostname),
            listName = { id -> backend.blocklistName(id) },
            onAllowDomain = { host ->
                guard.run {
                    scope.launch {
                        runCatching { backend.allowDomain(host) }
                            .onSuccess { Toast.makeText(context, s.format(S.AllowDomainDone, host), Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, it.message.orEmpty(), Toast.LENGTH_SHORT).show() }
                    }
                }
                selected = null
            },
            onDenyDomain = { host ->
                guard.run {
                    scope.launch {
                        runCatching { backend.denyDomain(host) }
                            .onSuccess { Toast.makeText(context, s.format(S.DenyDomainDone, host), Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, it.message.orEmpty(), Toast.LENGTH_SHORT).show() }
                    }
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
    return "pocketgate-logs-$date-$time.csv"
}

private fun formatAge(millis: Long): String {
    val sec = (millis / 1000L).coerceAtLeast(0L)
    val min = sec / 60L
    val rem = sec % 60L
    return if (min > 0) "%d:%02d".format(min, rem) else "${rem}s"
}

@Composable
private fun LiveConnectionRow(
    conn: AppBackend.LiveConnection,
    onCut: () -> Unit,
) {
    val s = rememberUiStrings()
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
    val target = conn.hostname?.let { "$it:${conn.remote.port}" }
        ?: "${conn.remote.address.hostAddress}:${conn.remote.port}"
    Card(
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = scheme.onSurface,
        ),
        shape = RoundedCornerShape(tokens.metricCornerRadius),
        border = border,
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(scheme.primary),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    conn.appLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    target,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "TCP · :${conn.local.port} · ${formatAge(conn.ageMillis)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = scheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCut) {
                Text(
                    s[S.CutConnection],
                    color = scheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LogRow(
    entry: LogEntry,
    tracker: com.networkcontrol.android.kotlin.engine.blocklist.TrackerInfo? = null,
    onClick: () -> Unit,
) {
    val s = rememberUiStrings()
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
    val allowed = entry.verdict.action == com.networkcontrol.android.kotlin.core.model.VerdictAction.Allow
    val accent = when {
        allowed -> scheme.primary
        entry.verdict.reason == VerdictReason.Blocklist -> scheme.tertiary
        else -> scheme.error
    }
    val target = entry.hostname ?: "${entry.remoteIp}:${entry.remotePort}"
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = scheme.onSurface,
        ),
        shape = RoundedCornerShape(tokens.metricCornerRadius),
        border = border,
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(accent),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    entry.appName ?: entry.appPath ?: s[S.FilterUnset],
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$target · ${protocolLabel(entry.protocol)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${reasonLabel(s, entry.verdict.reason)} · ${formatDateTime(entry.lastTs)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = scheme.onSurfaceVariant,
                )
                if (tracker != null) {
                    Text(
                        "${s[S.TrackerLabel]}：${tracker.company} · ${com.networkcontrol.android.kotlin.ui.common.trackerCategoryLabel(s, tracker.category)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.tertiary,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "×${entry.count}",
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (allowed) s[S.Allow] else s[S.Deny],
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                    )
                }
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
    onDenyDomain: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = rememberUiStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.LogDetail]) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val timeText = if (entry.count > 1 && entry.lastTs != entry.firstTs) {
                    "${formatDateTime(entry.firstTs)} – ${formatDateTime(entry.lastTs)}"
                } else {
                    formatDateTime(entry.lastTs)
                }
                DetailRow(s[S.Time], timeText)
                DetailRow(s[S.App], entry.appName ?: entry.appPath ?: "—")
                DetailRow(s[S.Target], entry.hostname ?: "${entry.remoteIp}:${entry.remotePort}")
                DetailRow(s[S.Protocol], protocolLabel(entry.protocol))
                DetailRow(s[S.Reason], reasonLabel(s, entry.verdict.reason))
                tracker?.let {
                    DetailRow(s[S.TrackerLabel], "${it.company} · ${com.networkcontrol.android.kotlin.ui.common.trackerCategoryLabel(s, it.category)}")
                }
                DetailRow(s[S.Count], entry.count.toString())
                entry.local?.let { DetailRow(s[S.LocalEndpoint], "${it.address.hostAddress}:${it.port}") }
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
            if (host != null) {
                if (entry.verdict.reason == VerdictReason.Blocklist) {
                    TextButton(onClick = { onAllowDomain(host) }) { Text(s[S.AllowDomain]) }
                } else if (entry.verdict.action == com.networkcontrol.android.kotlin.core.model.VerdictAction.Allow) {
                    TextButton(onClick = { onDenyDomain(host) }) {
                        Text(s[S.DenyDomain], color = MaterialTheme.colorScheme.error)
                    }
                }
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
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}
