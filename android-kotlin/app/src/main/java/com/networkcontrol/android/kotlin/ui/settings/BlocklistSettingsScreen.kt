package com.networkcontrol.android.kotlin.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.networkcontrol.android.kotlin.core.model.RemoteTarget
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.engine.blocklist.BlocklistCatalog
import com.networkcontrol.android.kotlin.engine.blocklist.BlocklistSourceState
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings
import com.networkcontrol.android.kotlin.ui.common.SectionCard
import com.networkcontrol.android.kotlin.ui.common.formatDateTime
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.launch
import java.text.NumberFormat

@Composable
fun BlocklistSettingsScreen(
    backend: AppBackend,
    onBack: () -> Unit,
    onOpenDomainRules: () -> Unit = {},
) {
    val guard = com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction.current
    val s = rememberUiStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by backend.blocklistState.collectAsStateWithLifecycle()
    val matcher by backend.blocklistMatcher.collectAsStateWithLifecycle()
    val ruleSet by backend.container.rules.ruleSet.collectAsStateWithLifecycle()
    val runtime by backend.runtime.collectAsStateWithLifecycle()
    val allowedDomainCount = remember(ruleSet.version, runtime.activeProfileId) {
        backend.ruleGroups().sumOf { group ->
            group.rules.count {
                it.conditions.remote is RemoteTarget.Domain &&
                    it.action == VerdictAction.Allow
            }
        }
    }
    val perConnection = backend.forwardingEnabled()
    var showAddDialog by remember { mutableStateOf(false) }
    var removingSource by remember { mutableStateOf<BlocklistSourceState?>(null) }
    val numbers = remember { NumberFormat.getIntegerInstance() }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
            runCatching { backend.importBlocklist(uri, name) }
                .onSuccess { toast(s.format(S.BlocklistsImported, it)) }
                .onFailure { toast(s.format(S.BlocklistsImportFailed, it.message.orEmpty())) }
        }
    }

    BackHandler { onBack() }

    SettingsSubScreenScaffold(title = s[S.Blocklists], onBack = onBack) {
            item {
                SectionCard {
                    Text(
                        s[S.BlocklistsSubtitle],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s[S.BlocklistsEnable], style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = { enabled ->
                                if (enabled) backend.setBlocklistsEnabled(true) else guard.run { backend.setBlocklistsEnabled(false) }
                            },
                        )
                    }
                    if (state.enabled) {
                        Text(
                            s.format(S.BlocklistsActive, numbers.format(matcher.totalEntries), state.lists.count { it.enabled && it.entries > 0 }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (!perConnection) {
                        Text(
                            s[S.BlocklistsModeWarning],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        s[S.BlocklistsPriority],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onOpenDomainRules,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (allowedDomainCount > 0) {
                                s.format(S.BlocklistsManageDomainRulesCount, allowedDomainCount)
                            } else {
                                s[S.BlocklistsManageDomainRules]
                            },
                        )
                    }
                }
            }

            item {
                TrackerCard(
                    source = state.trackerSource,
                    blocklistsEnabled = state.enabled,
                    blocked = state.blockedTrackerCategories,
                    database = matcher.trackers,
                    s = s,
                    numbers = numbers,
                    onToggle = { enabled -> guard.run { backend.setTrackerDatabaseEnabled(enabled) } },
                    onCategory = { category, on -> guard.run { backend.setTrackerCategoryBlocked(category, on) } },
                )
            }

            if (state.enabled) {
                item {
                    Text(s[S.BlocklistsSources], style = MaterialTheme.typography.titleMedium)
                }
                items(state.lists, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        s = s,
                        numbers = numbers,
                        onToggle = { enabled -> guard.run { backend.setBlocklistSourceEnabled(source.id, enabled) } },
                        onRemove = { removingSource = source },
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !state.updating,
                            onClick = {
                                if (backend.offlineMode()) {
                                    toast(s[S.OfflineModeBlocked])
                                    return@Button
                                }
                                scope.launch {
                                    val result = backend.updateBlocklistsNow()
                                    toast(s.format(S.BlocklistsUpdateResult, result.updated, result.unchanged, result.failed))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.updating) s[S.BlocklistsUpdating] else s[S.BlocklistsUpdateNow], maxLines = 1, softWrap = false) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { showAddDialog = true },
                                modifier = Modifier.weight(1f),
                            ) { Text(s[S.BlocklistsAddUrl], maxLines = 1, softWrap = false) }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                                modifier = Modifier.weight(1f),
                            ) { Text(s[S.BlocklistsImport], maxLines = 1, softWrap = false) }
                        }
                    }
                }
                item {
                    SettingsGroup(s[S.BlocklistsAutoUpdate]) {
                        SettingsSwitchTile(
                            title = s[S.BlocklistsWifiOnly],
                            subtitle = s[S.BlocklistsWifiOnlyHint],
                            checked = state.wifiOnly,
                            onCheckedChange = { backend.setBlocklistWifiOnly(it) },
                        )
                        SettingsDivider(insetWithIcon = false)
                        SettingsSegmentedRow(
                            title = s[S.BlocklistsInterval],
                            currentBadge = s.format(S.BlocklistsDays, state.intervalDays),
                            options = listOf(1, 3, 7, 14).map { days -> days to s.format(S.BlocklistsDays, days) },
                            selected = state.intervalDays,
                            onSelect = { backend.setBlocklistIntervalDays(it) },
                        )
                    }
                }
                item {
                    Text(
                        s[S.BlocklistsFormats],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }

    if (showAddDialog) {
        AddBlocklistDialog(
            s = s,
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                scope.launch {
                    val error = backend.addBlocklist(name, url)
                    if (error == null) {
                        showAddDialog = false
                    } else {
                        toast(s[S.BlocklistsInvalidUrl])
                    }
                }
            },
        )
    }

    removingSource?.let { source ->
        AlertDialog(
            onDismissRequest = { removingSource = null },
            title = { Text(s[S.BlocklistsRemove]) },
            text = { Text(s[S.RemoveBlocklistConfirm]) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = source.id
                        removingSource = null
                        guard.run { scope.launch { backend.removeBlocklist(id) } }
                    },
                ) { Text(s[S.BlocklistsRemove]) }
            },
            dismissButton = {
                TextButton(onClick = { removingSource = null }) { Text(s[S.Cancel]) }
            },
        )
    }
}

@Composable
private fun SourceCard(
    source: BlocklistSourceState,
    s: UiStrings,
    numbers: NumberFormat,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val builtin = BlocklistCatalog.find(source.id)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(source.name, style = MaterialTheme.typography.bodyLarge)
                val description = builtin?.description?.get(s.noteLanguage)
                    ?: if (source.imported) s[S.BlocklistsImportedFile] else source.urls.firstOrNull().orEmpty()
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val status = when {
                    source.lastError != null && source.updatedAt == 0L -> s.format(S.BlocklistsFailed, source.lastError)
                    source.updatedAt == 0L -> s[S.BlocklistsNotDownloaded]
                    else -> s.format(S.BlocklistsEntries, numbers.format(source.entries), formatDateTime(source.updatedAt))
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (source.lastError != null && source.updatedAt == 0L) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (source.lastError != null && source.updatedAt > 0L) {
                    Text(
                        s.format(S.BlocklistsFailed, source.lastError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(checked = source.enabled, onCheckedChange = onToggle)
        }
        if (!source.builtin) {
            TextButton(onClick = onRemove) { Text(s[S.BlocklistsRemove]) }
        }
    }
}

@Composable
private fun TrackerCard(
    source: BlocklistSourceState?,
    blocklistsEnabled: Boolean,
    blocked: Set<String>,
    database: com.networkcontrol.android.kotlin.engine.blocklist.TrackerDatabase?,
    s: UiStrings,
    numbers: NumberFormat,
    onToggle: (Boolean) -> Unit,
    onCategory: (String, Boolean) -> Unit,
) {
    val enabled = source != null && source.enabled
    SectionCard(s[S.Trackers]) {
        Text(
            BlocklistCatalog.trackers.description[s.noteLanguage],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s[S.TrackersEnable], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        if (source != null && source.enabled) {
            val status = when {
                database != null -> s.format(
                    S.TrackersStatus,
                    numbers.format(database.size),
                    database.companies,
                    formatDateTime(source.updatedAt),
                )
                source.lastError != null -> s.format(S.BlocklistsFailed, source.lastError)
                else -> s[S.BlocklistsNotDownloaded]
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (database == null && source.lastError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(s[S.TrackersBlockCategories], style = MaterialTheme.typography.bodyMedium)
            if (!blocklistsEnabled) {
                Text(
                    s[S.TrackersNeedBlocklists],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            com.networkcontrol.android.kotlin.engine.blocklist.TrackerCategory.blockable.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        com.networkcontrol.android.kotlin.ui.common.trackerCategoryLabel(s, category),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = category in blocked,
                        enabled = blocklistsEnabled,
                        onCheckedChange = { onCategory(category, it) },
                    )
                }
            }
        }
        Text(
            s[S.TrackersAttribution],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddBlocklistDialog(s: UiStrings, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.BlocklistsAddUrl]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s[S.BlocklistsName]) }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it.trim() }, label = { Text(s[S.BlocklistsUrl]) }, singleLine = true)
                Text(
                    s[S.BlocklistsFormats],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, url) }) { Text(s[S.Add]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[S.Cancel]) } },
    )
}
