package com.networkcontrol.android.kotlin.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.platform.permissions.DumpGrantOutcome
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.SectionCard
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 聯網詢問與懸浮面板的獨立設定頁：整合詢問顯示方式、懸浮窗位置、超時與時效設定。
 */
@Composable
fun PromptSettingsScreen(backend: AppBackend, onBack: () -> Unit) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by backend.settings.collectAsStateWithLifecycle()

    var overlayKey by remember { mutableIntStateOf(0) }
    var exitDetection by remember { mutableStateOf(backend.processExitDetection()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        overlayKey++
        exitDetection = backend.processExitDetection()
    }
    val canDrawOverlays = remember(overlayKey) { backend.canDrawOverlays() }
    var promptMode by remember { mutableStateOf(backend.promptPresentationMode()) }
    var promptPosition by remember { mutableStateOf(backend.promptOverlayPosition()) }
    var promptRatio by remember { mutableFloatStateOf(backend.promptOverlayCustomRatio()) }
    var showOverlayPositionDialog by remember { mutableStateOf(false) }
    var promptDetails by remember { mutableStateOf(backend.promptShowDetails()) }
    var hideOnLock by remember { mutableStateOf(backend.promptHideDetailsOnLockScreen()) }
    var showTimedOption by remember { mutableStateOf(backend.promptShowTimedOption()) }
    var denyPermanent by remember { mutableStateOf(backend.promptDenyPermanent()) }

    var onceBackgroundMinutes by remember { mutableIntStateOf(backend.onceBackgroundMinutes()) }
    var grantingExitDetection by remember { mutableStateOf(false) }
    var showExitGrantGuide by remember { mutableStateOf(false) }

    var editingNumberTarget by remember { mutableStateOf<PromptNumberTarget?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ ${s[S.Back]}") }
            Text(
                s[S.PromptSettings],
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(s[S.PromptPresentation]) {
                    val autoOverlay = promptMode == "auto"
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = autoOverlay,
                            onClick = {
                                promptMode = "auto"
                                backend.setPromptPresentationMode("auto")
                            },
                            label = { Text(s[S.PromptPresentationAuto]) },
                        )
                        FilterChip(
                            selected = !autoOverlay,
                            onClick = {
                                promptMode = "notification"
                                backend.setPromptPresentationMode("notification")
                            },
                            label = { Text(s[S.PromptPresentationNotification]) },
                        )
                    }
                    Text(
                        s[S.PromptPresentationHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (autoOverlay) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s[S.OverlayPermission],
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (canDrawOverlays) s[S.OverlayGranted] else s[S.OverlayDenied],
                                style = MaterialTheme.typography.bodySmall,
                                color = if (canDrawOverlays) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            if (!canDrawOverlays) {
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { context.startActivity(backend.overlayPermissionIntent()) }) {
                                    Text(s[S.OverlayGrant])
                                }
                            }
                        }
                        Text(s[S.OverlayPosition], style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = promptPosition == "top",
                                onClick = {
                                    promptPosition = "top"
                                    backend.setPromptOverlayPosition("top")
                                },
                                label = { Text(s[S.OverlayTop]) },
                            )
                            FilterChip(
                                selected = promptPosition == "bottom",
                                onClick = {
                                    promptPosition = "bottom"
                                    backend.setPromptOverlayPosition("bottom")
                                },
                                label = { Text(s[S.OverlayBottom]) },
                            )
                            FilterChip(
                                selected = promptPosition == "custom",
                                onClick = {
                                    promptPosition = "custom"
                                    backend.setPromptOverlayPosition("custom")
                                },
                                label = { Text(s[S.OverlayCustom]) },
                            )
                        }
                        if (promptPosition == "custom") {
                            OutlinedButton(onClick = { showOverlayPositionDialog = true }) {
                                Text("${s[S.OverlayAdjustPosition]} (${(promptRatio * 100).roundToInt()}%)")
                            }
                            Text(
                                s[S.OverlayCustomHint],
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            s[S.OverlayOemHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(s[S.PromptContentSettings], style = MaterialTheme.typography.bodyMedium)
                    SwitchRow(
                        title = s[S.ShowPromptDetails],
                        checked = promptDetails,
                        onCheckedChange = {
                            promptDetails = it
                            backend.setPromptShowDetails(it)
                        },
                    )
                    SwitchRow(
                        title = s[S.HidePromptDetailsOnLock],
                        checked = hideOnLock,
                        enabled = promptDetails,
                        onCheckedChange = {
                            hideOnLock = it
                            backend.setPromptHideDetailsOnLockScreen(it)
                        },
                    )
                }
            }

            item {
                SectionCard(s[S.PromptSettings]) {
                    CompactValueRow(
                        title = s[S.PromptTimeout],
                        valueText = "${settings.promptTimeoutSeconds}s",
                        actionText = s[S.Edit],
                        onClick = { editingNumberTarget = PromptNumberTarget.Timeout },
                    )
                    CompactValueRow(
                        title = s[S.PromptTimedDuration],
                        valueText = "${settings.promptTimedMinutes}m",
                        actionText = s[S.Edit],
                        onClick = { editingNumberTarget = PromptNumberTarget.TimedDuration },
                    )
                    CompactValueRow(
                        title = s[S.OnceBackgroundMinutes],
                        subtitle = s[S.OnceBackgroundMinutesHint],
                        valueText = "${onceBackgroundMinutes}m",
                        actionText = s[S.Edit],
                        onClick = { editingNumberTarget = PromptNumberTarget.OnceBackground },
                    )
                    // 關閉偵測（DUMP）：已授權時「僅一次」「禁止」在應用真正關閉時才失效。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (exitDetection) s[S.OnceExitDetectionOn] else s[S.OnceExitDetectionOff],
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (exitDetection) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (!exitDetection) {
                            TextButton(
                                enabled = !grantingExitDetection,
                                onClick = {
                                    grantingExitDetection = true
                                    scope.launch {
                                        val outcome = backend.grantExitDetection()
                                        grantingExitDetection = false
                                        exitDetection = backend.processExitDetection()
                                        when (outcome) {
                                            DumpGrantOutcome.Granted ->
                                                toast(if (exitDetection) s[S.OnceExitGrantOk] else s[S.OnceExitGrantRestart])
                                            DumpGrantOutcome.NoTool ->
                                                showExitGrantGuide = true
                                            DumpGrantOutcome.Denied ->
                                                toast(s[S.OnceExitGrantDenied])
                                            is DumpGrantOutcome.Failed ->
                                                toast(s.format(S.OnceExitGrantFailed, outcome.message))
                                        }
                                    }
                                },
                            ) { Text(s[S.OnceExitGrant]) }
                        }
                    }
                    SwitchRow(
                        title = s[S.PromptTimedVisible],
                        subtitle = s[S.PromptTimedVisibleHint],
                        checked = showTimedOption,
                        onCheckedChange = {
                            showTimedOption = it
                            backend.setPromptShowTimedOption(it)
                        },
                    )
                    SwitchRow(
                        title = s[S.PromptDenyPermanent],
                        subtitle = s[S.PromptDenyPermanentHint],
                        checked = denyPermanent,
                        onCheckedChange = {
                            denyPermanent = it
                            backend.setPromptDenyPermanent(it)
                        },
                    )
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
                }
            }
        }
    }

    if (showOverlayPositionDialog) {
        OverlayPositionDialog(
            initialRatio = promptRatio,
            onDismiss = { showOverlayPositionDialog = false },
            onSave = { ratio ->
                promptRatio = ratio
                backend.setPromptOverlayCustomRatio(ratio)
                showOverlayPositionDialog = false
            },
        )
    }

    if (showExitGrantGuide) {
        val command = remember { backend.exitDetectionAdbCommand() }
        AlertDialog(
            onDismissRequest = { showExitGrantGuide = false },
            title = { Text(s[S.OnceExitGuideTitle]) },
            text = {
                SelectionContainer {
                    Text(s[S.OnceExitGuideBody] + "\n\n" + command)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("adb", command))
                        toast(s[S.Done])
                    },
                ) { Text(s[S.Copy]) }
            },
            dismissButton = {
                TextButton(onClick = { showExitGrantGuide = false }) { Text(s[S.Close]) }
            },
        )
    }

    editingNumberTarget?.let { target ->
        val (title, currentValue, presets) = when (target) {
            PromptNumberTarget.Timeout -> Triple(
                s[S.PromptTimeout],
                settings.promptTimeoutSeconds,
                listOf(15, 30, 60, 120),
            )
            PromptNumberTarget.TimedDuration -> Triple(
                s[S.PromptTimedDuration],
                settings.promptTimedMinutes,
                listOf(15, 30, 60, 120),
            )
            PromptNumberTarget.OnceBackground -> Triple(
                s[S.OnceBackgroundMinutes],
                onceBackgroundMinutes,
                listOf(0, 5, 10, 30),
            )
        }
        NumberInputDialog(
            title = title,
            initialValue = currentValue,
            presets = presets,
            minValue = if (target == PromptNumberTarget.OnceBackground) 0 else 1,
            onDismiss = { editingNumberTarget = null },
            onSave = { newValue ->
                when (target) {
                    PromptNumberTarget.Timeout ->
                        backend.updateSettings { it.copy(promptTimeoutSeconds = newValue) }
                    PromptNumberTarget.TimedDuration ->
                        backend.updateSettings { it.copy(promptTimedMinutes = newValue) }
                    PromptNumberTarget.OnceBackground -> {
                        backend.setOnceBackgroundMinutes(newValue)
                        onceBackgroundMinutes = backend.onceBackgroundMinutes()
                    }
                }
                editingNumberTarget = null
                toast(s[S.Done])
            },
        )
    }
}

private enum class PromptNumberTarget {
    Timeout,
    TimedDuration,
    OnceBackground,
}

@Composable
internal fun CompactValueRow(
    title: String,
    valueText: String,
    actionText: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Spacer(Modifier.width(8.dp))
        Text(
            valueText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = onClick) {
            Text(actionText)
        }
    }
}

@Composable
private fun NumberInputDialog(
    title: String,
    initialValue: Int,
    presets: List<Int>,
    minValue: Int = 1,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val s = rememberUiStrings()
    var text by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    val parsed = text.toIntOrNull()
    val isValid = parsed != null && parsed >= minValue

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = parsed == preset,
                            onClick = { text = preset.toString() },
                            label = { Text(preset.toString()) },
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { value -> text = value.filter { it.isDigit() }.take(4) },
                    isError = !isValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { parsed?.let(onSave) },
            ) { Text(s[S.Save]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Cancel]) }
        },
    )
}
