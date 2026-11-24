package com.networkcontrol.android.kotlin.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.focus.FocusSchedule
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.SectionCard
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction

/**
 * 配置檔案與專注時間表的獨立管理頁。
 */
@Composable
fun ProfileScheduleSettingsScreen(backend: AppBackend, onBack: () -> Unit) {
    val guard = LocalSensitiveAction.current
    val s = rememberUiStrings()
    val context = LocalContext.current
    val profiles by backend.profiles.collectAsStateWithLifecycle()
    val runtime by backend.runtime.collectAsStateWithLifecycle()
    val focusSchedules by backend.focusSchedules.collectAsStateWithLifecycle()

    var editingProfile by remember { mutableStateOf<Profile?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var deletingProfile by remember { mutableStateOf<Profile?>(null) }
    var editingSchedule by remember { mutableStateOf<FocusSchedule?>(null) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var deletingSchedule by remember { mutableStateOf<FocusSchedule?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    LaunchedEffect(Unit) {
        backend.profileErrors.collect { message -> toast(message) }
    }

    BackHandler { onBack() }

    SettingsSubScreenScaffold(
        title = "${s[S.ProfileSettings]} · ${s[S.FocusSchedule]}",
        onBack = onBack,
    ) {
            item {
                SectionCard(s[S.ProfileSettings]) {
                    profiles.forEach { profile ->
                        ProfileRow(
                            profile = profile,
                            active = profile.id == runtime.activeProfileId,
                            s = s,
                            onActivate = { guard.run { backend.activateProfile(profile.id) } },
                            onEdit = {
                                editingProfile = profile
                                showProfileDialog = true
                            },
                            onDelete = { deletingProfile = profile },
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                editingProfile = null
                                showProfileDialog = true
                            },
                        ) { Text(s[S.AddProfile]) }
                        OutlinedButton(onClick = { backend.createFocusProfile(copyFromCurrent = true) }) {
                            Text(s[S.CreateFocusProfile])
                        }
                    }
                }
            }

            item {
                SectionCard(s[S.FocusSchedule]) {
                    Text(
                        s[S.ScheduleHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (focusSchedules.isEmpty()) {
                        Text(
                            s[S.NoFocusSchedules],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    focusSchedules.forEach { schedule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingSchedule = schedule
                                    showScheduleDialog = true
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${schedule.dayLabel(context)} ${schedule.timeLabel()}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Switch(
                                checked = schedule.enabled,
                                onCheckedChange = { enabled ->
                                    guard.run { backend.setFocusScheduleEnabled(schedule.id, enabled) }
                                },
                            )
                            TextButton(onClick = { deletingSchedule = schedule }) {
                                Text(s[S.Delete])
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            editingSchedule = null
                            showScheduleDialog = true
                        },
                    ) { Text(s[S.AddSchedule]) }
                }
            }
    }

    if (showProfileDialog) {
        ProfileDialog(
            profile = editingProfile,
            currentNetwork = backend.container.network.current,
            loadSims = { backend.subscriptions() },
            onDismiss = { showProfileDialog = false },
            onSave = { name, mode, auto ->
                val current = editingProfile
                guard.run {
                    if (current == null) backend.createProfile(name, mode, auto)
                    else backend.updateProfile(current.id, name, mode, auto)
                }
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
                    val id = profile.id
                    deletingProfile = null
                    guard.run { backend.deleteProfile(id) }
                }) { Text(s[S.Delete]) }
            },
            dismissButton = {
                TextButton(onClick = { deletingProfile = null }) { Text(s[S.Cancel]) }
            },
        )
    }

    if (showScheduleDialog) {
        val scheduleApps by androidx.compose.runtime.produceState<List<com.networkcontrol.android.kotlin.core.model.InstalledApp>>(
            initialValue = emptyList(),
            showScheduleDialog,
        ) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                backend.installedApps()
            }
        }
        ScheduleDialog(
            schedule = editingSchedule,
            apps = scheduleApps,
            onDismiss = { showScheduleDialog = false },
            onSave = { schedule ->
                backend.saveFocusSchedule(schedule)
                showScheduleDialog = false
            },
        )
    }

    deletingSchedule?.let { schedule ->
        AlertDialog(
            onDismissRequest = { deletingSchedule = null },
            title = { Text(s[S.FocusSchedule]) },
            text = { Text(s[S.DeleteScheduleConfirm]) },
            confirmButton = {
                TextButton(onClick = {
                    val id = schedule.id
                    deletingSchedule = null
                    guard.run { backend.deleteFocusSchedule(id) }
                }) { Text(s[S.Delete]) }
            },
            dismissButton = {
                TextButton(onClick = { deletingSchedule = null }) { Text(s[S.Cancel]) }
            },
        )
    }
}
