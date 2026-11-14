package com.networkcontrol.android.kotlin.ui.settings

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.BuildConfig
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.RuleImportMode
import com.networkcontrol.android.kotlin.core.model.RuleImportPreview
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.ScreenHeader
import com.networkcontrol.android.kotlin.ui.common.SectionCard
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(backend: AppBackend) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by backend.settings.collectAsStateWithLifecycle()
    val runtime by backend.runtime.collectAsStateWithLifecycle()
    val profiles by backend.profiles.collectAsStateWithLifecycle()
    val plan by backend.plan.collectAsStateWithLifecycle()

    var timeoutText by remember(settings.promptTimeoutSeconds) {
        mutableStateOf(settings.promptTimeoutSeconds.toString())
    }
    var editingProfile by remember { mutableStateOf<Profile?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var deletingProfile by remember { mutableStateOf<Profile?>(null) }
    var importJson by remember { mutableStateOf<String?>(null) }
    var previewMerge by remember { mutableStateOf<RuleImportPreview?>(null) }
    var previewReplace by remember { mutableStateOf<RuleImportPreview?>(null) }
    var importMode by remember { mutableStateOf(RuleImportMode.Merge) }
    var showImportDialog by remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(backend.exportRules().toByteArray(Charsets.UTF_8))
                    }
                }
            }.onSuccess { toast(s[S.ExportDone]) }
                .onFailure { toast(s.format(S.ExportFailed, it.message.orEmpty())) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
            }.onSuccess { json ->
                if (json == null) {
                    toast(s.format(S.ImportFailed, "empty file"))
                    return@onSuccess
                }
                importJson = json
                previewMerge = runCatching { backend.previewImport(json, RuleImportMode.Merge) }.getOrNull()
                previewReplace = runCatching { backend.previewImport(json, RuleImportMode.Replace) }.getOrNull()
                if (previewMerge == null) {
                    toast(s.format(S.ImportFailed, "invalid file"))
                } else {
                    showImportDialog = true
                }
            }.onFailure { toast(s.format(S.ImportFailed, it.message.orEmpty())) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader(s[S.Settings], s[S.SettingsSubtitle]) }

        item {
            SectionCard(s[S.Appearance]) {
                DropdownSetting(
                    label = s[S.Language],
                    current = when (backend.container.preferences.languageTag) {
                        "zh-CN" -> s[S.ChineseSimplified]
                        "zh-TW" -> s[S.ChineseTraditional]
                        "en-US" -> s[S.English]
                        else -> s[S.SystemDefault]
                    },
                    options = listOf(
                        s[S.SystemDefault] to null,
                        s[S.ChineseSimplified] to "zh-CN",
                        s[S.ChineseTraditional] to "zh-TW",
                        s[S.English] to "en-US",
                    ),
                    onSelected = { backend.setLanguage(it) },
                )
                DropdownSetting(
                    label = s[S.Theme],
                    current = when (backend.container.preferences.themeMode) {
                        "light" -> s[S.ThemeLight]
                        "dark" -> s[S.ThemeDark]
                        else -> s[S.ThemeSystem]
                    },
                    options = listOf(
                        s[S.ThemeSystem] to "system",
                        s[S.ThemeLight] to "light",
                        s[S.ThemeDark] to "dark",
                    ),
                    onSelected = { backend.setTheme(it ?: "system") },
                )
            }
        }

        item {
            SectionCard(s[S.PromptSettings]) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s[S.PromptTimeout], style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedTextField(
                        value = timeoutText,
                        onValueChange = { value ->
                            timeoutText = value.filter { it.isDigit() }.take(4)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                    TextButton(
                        onClick = {
                            val seconds = timeoutText.toIntOrNull() ?: settings.promptTimeoutSeconds
                            backend.updateSettings { it.copy(promptTimeoutSeconds = seconds) }
                            toast(s[S.Done])
                        },
                    ) { Text(s[S.Save]) }
                }
                SwitchRow(
                    title = s[S.PromptDefaultAction],
                    subtitle = if (settings.promptDefaultAction == VerdictAction.Deny) s[S.Deny] else s[S.Allow],
                    checked = settings.promptDefaultAction == VerdictAction.Allow,
                    onCheckedChange = { allow ->
                        backend.updateSettings {
                            it.copy(promptDefaultAction = if (allow) VerdictAction.Allow else VerdictAction.Deny)
                        }
                    },
                )
                SwitchRow(
                    title = s[S.AllowIcmp],
                    checked = settings.allowIcmpByDefault,
                    onCheckedChange = { allow ->
                        backend.updateSettings { it.copy(allowIcmpByDefault = allow) }
                    },
                )
            }
        }

        item {
            SectionCard(s[S.Notifications]) {
                SwitchRow(
                    title = s[S.NotificationsEnabled],
                    checked = backend.container.preferences.notificationsEnabled,
                    onCheckedChange = {
                        backend.container.preferences.notificationsEnabled = it
                    },
                )
                OutlinedButton(onClick = { context.startActivity(backend.openNotificationSettings()) }) {
                    Text(s[S.OpenNotificationSettings])
                }
            }
        }

        item {
            SectionCard(s[S.VpnSettings]) {
                OutlinedButton(onClick = { context.startActivity(backend.openAlwaysOnVpnSettings()) }) {
                    Text(s[S.AlwaysOnVpn])
                }
                OutlinedButton(onClick = { context.startActivity(backend.openBatteryOptimizationSettings()) }) {
                    Text(s[S.BatteryOptimization])
                }
                Text(
                    s[S.VpnConsentRequired],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(s[S.DataSettings]) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s[S.UsageAccess], style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (backend.hasTrafficAccess()) s[S.UsageAccessGranted] else s[S.UsageAccessDenied],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!backend.hasTrafficAccess()) {
                        OutlinedButton(onClick = { context.startActivity(backend.trafficAccessIntent()) }) {
                            Text(s[S.GrantAccess])
                        }
                    }
                }
            }
        }

        item {
            SectionCard(s[S.ProfileSettings]) {
                profiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        active = profile.id == runtime.activeProfileId,
                        s = s,
                        onActivate = { backend.activateProfile(profile.id) },
                        onEdit = {
                            editingProfile = profile
                            showProfileDialog = true
                        },
                        onDelete = { deletingProfile = profile },
                    )
                }
                OutlinedButton(
                    onClick = {
                        editingProfile = null
                        showProfileDialog = true
                    },
                ) { Text(s[S.AddProfile]) }
            }
        }

        item {
            SectionCard(s[S.Transfer]) {
                OutlinedButton(onClick = { exportLauncher.launch("networkcontrol-rules.json") }) {
                    Text(s[S.ExportRules])
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                    Text(s[S.ImportRules])
                }
            }
        }

        item {
            SectionCard(s[S.Diagnostics]) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { backend.exportDiagnostics() } }
                                .onSuccess { file ->
                                    val uri = backend.diagnosticsShareUri(file)
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/zip"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, s[S.Share]))
                                }
                                .onFailure { toast(s.format(S.ExportFailed, it.message.orEmpty())) }
                        }
                    },
                ) { Text(s[S.ExportDiagnostics]) }
            }
        }

        item {
            SectionCard(s[S.About]) {
                DetailLine(s[S.Version], "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                DetailLine(s[S.Engine], "AndroidVpn")
                DetailLine(s[S.Status], "${runtime.mode.name} / ${backend.protectionStatus().name}")
                DetailLine(s[S.SkippedRules], plan.skippedRules.size.toString())
                DetailLine(s[S.AppsCount], backend.container.appCatalog.getApps().size.toString())
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showProfileDialog) {
        ProfileDialog(
            profile = editingProfile,
            onDismiss = { showProfileDialog = false },
            onSave = { name, mode ->
                val current = editingProfile
                if (current == null) backend.createProfile(name, mode)
                else backend.updateProfile(current.id, name, mode)
                showProfileDialog = false
            },
        )
    }

    deletingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text(s[S.DeleteProfile]) },
            text = { Text(s[S.DeleteProfileConfirm]) },
            confirmButton = {
                TextButton(onClick = {
                    backend.deleteProfile(profile.id)
                    deletingProfile = null
                }) { Text(s[S.Delete]) }
            },
            dismissButton = {
                TextButton(onClick = { deletingProfile = null }) { Text(s[S.Cancel]) }
            },
        )
    }

    if (showImportDialog) {
        ImportDialog(
            preview = if (importMode == RuleImportMode.Merge) previewMerge else previewReplace,
            mode = importMode,
            onModeChange = { importMode = it },
            onDismiss = { showImportDialog = false },
            onApply = {
                val json = importJson
                if (json != null) {
                    scope.launch {
                        runCatching { backend.applyImport(json, importMode) }
                            .onSuccess { toast(s[S.ImportDone]) }
                            .onFailure { toast(s.format(S.ImportFailed, it.message.orEmpty())) }
                    }
                }
                showImportDialog = false
            },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: Profile,
    active: Boolean,
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                profile.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                "${profile.mode.name}${if (active) " · ${s[S.ActiveProfile]}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!active) {
            TextButton(onClick = onActivate) { Text(s[S.Open]) }
        }
        TextButton(onClick = onEdit) { Text(s[S.Edit]) }
        if (profile.id != Profile.DEFAULT_ID) {
            TextButton(onClick = onDelete) { Text(s[S.Delete]) }
        }
    }
}

@Composable
private fun ProfileDialog(
    profile: Profile?,
    onDismiss: () -> Unit,
    onSave: (String, FilterMode) -> Unit,
) {
    val s = rememberUiStrings()
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var mode by remember { mutableStateOf(profile?.mode ?: FilterMode.Ask) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) s[S.AddProfile] else s[S.Edit]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(s[S.ProfileName]) },
                    singleLine = true,
                )
                Text(s[S.ProfileMode], style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == FilterMode.Ask, { mode = FilterMode.Ask }, { Text(s[S.ModeAsk]) })
                    FilterChip(mode == FilterMode.Whitelist, { mode = FilterMode.Whitelist }, { Text(s[S.ModeWhitelist]) })
                    FilterChip(mode == FilterMode.Blacklist, { mode = FilterMode.Blacklist }, { Text(s[S.ModeBlacklist]) })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) onSave(name.trim(), mode)
                },
                enabled = name.isNotBlank(),
            ) { Text(s[S.Save]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

@Composable
private fun ImportDialog(
    preview: RuleImportPreview?,
    mode: RuleImportMode,
    onModeChange: (RuleImportMode) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    val s = rememberUiStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[S.ImportPreview]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == RuleImportMode.Merge, { onModeChange(RuleImportMode.Merge) }, { Text(s[S.Merge]) })
                    FilterChip(mode == RuleImportMode.Replace, { onModeChange(RuleImportMode.Replace) }, { Text(s[S.Replace]) })
                }
                if (preview == null) {
                    Text(s[S.ImportFailed], color = MaterialTheme.colorScheme.error)
                } else {
                    DetailLine(s[S.Added], preview.added.toString())
                    DetailLine(s[S.Updated], preview.updated.toString())
                    DetailLine(s[S.Removed], preview.removed.toString())
                    DetailLine(s[S.Unchanged], preview.unchanged.toString())
                    DetailLine(s[S.Ineffective], preview.ineffective.toString())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = preview != null) { Text(s[S.Done]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DropdownSetting(
    label: String,
    current: String,
    options: List<Pair<String, String?>>,
    onSelected: (String?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            var expanded by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { expanded = true }) {
                Text(current)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (text, value) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelected(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(
            "$label：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
