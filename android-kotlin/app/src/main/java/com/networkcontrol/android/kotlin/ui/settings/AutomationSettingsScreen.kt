package com.networkcontrol.android.kotlin.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.networkcontrol.android.automation.model.GroupOverride
import com.networkcontrol.android.automation.model.RawSubscription
import com.networkcontrol.android.automation.model.SubscriptionRecord
import com.networkcontrol.android.automation.model.TriggerLogEntry
import com.networkcontrol.android.automation.network.SubscriptionFetcher
import com.networkcontrol.android.automation.platform.AccessibilityGrantOutcome
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.SectionCard
import com.networkcontrol.android.kotlin.ui.common.formatDateTime
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AutomationSettingsScreen(backend: AppBackend, onBack: () -> Unit) {
    val s = rememberUiStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var resumeKey by remember { mutableIntStateOf(0) }
    var featureEnabled by remember { mutableStateOf(backend.automationFeatureEnabled()) }
    var serviceActive by remember(resumeKey) { mutableStateOf(backend.automationServiceActive()) }
    var shizukuAvailable by remember(resumeKey) { mutableStateOf(backend.automationShizukuRunning()) }

    var subscriptions by remember { mutableStateOf<List<SubscriptionRecord>>(emptyList()) }
    var logs by remember { mutableStateOf<List<TriggerLogEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    var showEnablePrompt by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addUrlText by remember { mutableStateOf("") }
    var deletingSub by remember { mutableStateOf<SubscriptionRecord?>(null) }
    var managingSub by remember { mutableStateOf<SubscriptionRecord?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun refreshData() {
        scope.launch {
            val (subs, triggerLogs) = withContext(Dispatchers.IO) {
                backend.listAutomationSubscriptions() to backend.listAutomationTriggerLogs(60)
            }
            subscriptions = subs
            logs = triggerLogs
            serviceActive = backend.automationServiceActive()
            shizukuAvailable = backend.automationShizukuRunning()
            featureEnabled = backend.automationFeatureEnabled()
        }
    }

    LaunchedEffect(Unit) { refreshData() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumeKey++
        refreshData()
    }

    fun tryEnableViaShizuku() {
        scope.launch {
            busy = true
            val outcome = backend.setAutomationAccessibilityViaShizuku(true)
            busy = false
            when (outcome) {
                AccessibilityGrantOutcome.Enabled -> {
                    toast(s[S.AutomationShizukuSuccess])
                    refreshData()
                }
                AccessibilityGrantOutcome.NoTool -> toast(s[S.AutomationShizukuNoTool])
                AccessibilityGrantOutcome.Denied -> toast(s[S.AutomationShizukuDenied])
                is AccessibilityGrantOutcome.Failed -> toast(s.format(S.AutomationShizukuFailed, outcome.message))
                AccessibilityGrantOutcome.Disabled -> refreshData()
            }
        }
    }

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ ${s[S.Back]}") }
            Text(
                "${s[S.AutomationTitle]} (${s[S.AutomationExperimentalBadge]})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. 總開關與服務狀態
            item {
                SectionCard {
                    Text(
                        s[S.AutomationSubtitle],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            s[S.AutomationEnableSwitch],
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Switch(
                            checked = featureEnabled,
                            onCheckedChange = { checked ->
                                featureEnabled = checked
                                backend.setAutomationFeatureEnabled(checked)
                                if (checked && !serviceActive) {
                                    showEnablePrompt = true
                                }
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            s[S.AutomationServiceStatus],
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (serviceActive) s[S.AutomationServiceOn] else s[S.AutomationServiceOff],
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (serviceActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Text(
                        s[S.AutomationShizukuHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { tryEnableViaShizuku() },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                s[S.AutomationEnableViaShizuku],
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = { context.startActivity(backend.openAccessibilitySettingsIntent()) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                s[S.AutomationOpenA11ySettings],
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // 2. 訂閱列表
            item {
                SectionCard(s[S.AutomationSubscriptions]) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                addUrlText = ""
                                showAddDialog = true
                            },
                            enabled = !busy,
                        ) {
                            Text(s[S.AutomationAddSubscription])
                        }
                        if (subscriptions.isNotEmpty()) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        var updated = 0
                                        var failed = 0
                                        for (sub in subscriptions) {
                                            val url = sub.updateUrl ?: continue
                                            when (backend.importAutomationSubscription(url, sub)) {
                                                is SubscriptionFetcher.FetchResult.Success -> updated++
                                                is SubscriptionFetcher.FetchResult.Error -> failed++
                                                is SubscriptionFetcher.FetchResult.UpToDate -> Unit
                                            }
                                        }
                                        busy = false
                                        refreshData()
                                        if (failed > 0) {
                                            toast(s.format(S.AutomationSubFailed, "$failed failed"))
                                        } else if (updated > 0) {
                                            toast(s.format(S.AutomationSubImported, "$updated"))
                                        } else {
                                            toast(s[S.AutomationSubUpToDate])
                                        }
                                    }
                                },
                            ) {
                                Text(s[S.AutomationUpdateAll])
                            }
                        }
                    }

                    if (subscriptions.isEmpty()) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val result = withContext(Dispatchers.IO) {
                                        backend.restoreDefaultAutomationSubscription()
                                    }
                                    busy = false
                                    result.onSuccess { restored ->
                                        toast(s.format(S.AutomationSubImported, restored.name))
                                    }.onFailure { err ->
                                        toast(s.format(S.AutomationSubFailed, err.message ?: err.javaClass.simpleName))
                                    }
                                    refreshData()
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(s[S.AutomationRestoreBuiltin])
                        }
                        Text(
                            s[S.AutomationNoSubscriptions],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        subscriptions.forEach { sub ->
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        sub.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        s.format(S.AutomationSubMeta, sub.version, sub.appCount, sub.groupCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (sub.skippedRuleCount > 0) {
                                        Text(
                                            s.format(S.AutomationSubSkipped, sub.skippedRuleCount),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                Switch(
                                    checked = sub.enabled,
                                    onCheckedChange = { enabled ->
                                        backend.setAutomationSubscriptionEnabled(sub.id, enabled)
                                        refreshData()
                                    },
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(onClick = { managingSub = sub }) {
                                    Text(s[S.AutomationManageGroups])
                                }
                                if (!sub.updateUrl.isNullOrBlank()) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            scope.launch {
                                                busy = true
                                                val res = backend.importAutomationSubscription(sub.updateUrl!!, sub)
                                                busy = false
                                                when (res) {
                                                    is SubscriptionFetcher.FetchResult.Success ->
                                                        toast(s.format(S.AutomationSubImported, res.parsed.subscription.name))
                                                    is SubscriptionFetcher.FetchResult.UpToDate ->
                                                        toast(s[S.AutomationSubUpToDate])
                                                    is SubscriptionFetcher.FetchResult.Error ->
                                                        toast(s.format(S.AutomationSubFailed, res.message))
                                                }
                                                refreshData()
                                            }
                                        },
                                    ) {
                                        Text(s[S.Refresh])
                                    }
                                }
                                TextButton(onClick = { deletingSub = sub }) {
                                    Text(s[S.Delete], color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            // 3. 觸發紀錄
            item {
                SectionCard(s[S.AutomationTriggerLogs]) {
                    if (logs.isEmpty()) {
                        Text(
                            s[S.AutomationNoLogs],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    backend.clearAutomationTriggerLogs()
                                    refreshData()
                                },
                            ) {
                                Text(s[S.AutomationClearLogs])
                            }
                        }
                        logs.take(30).forEach { entry ->
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Text(
                                "${entry.groupName} · ${entry.appId}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatDateTime(entry.timestampMs)} · ${entry.subscriptionName} · ${entry.action}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    // 開啟時無障礙未授權引導彈窗
    if (showEnablePrompt) {
        AlertDialog(
            onDismissRequest = { showEnablePrompt = false },
            title = { Text(s[S.AutomationEnablePromptTitle]) },
            text = { Text(s[S.AutomationEnablePromptBody]) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnablePrompt = false
                        if (shizukuAvailable) {
                            tryEnableViaShizuku()
                        } else {
                            context.startActivity(backend.openAccessibilitySettingsIntent())
                        }
                    },
                ) {
                    Text(
                        if (shizukuAvailable) s[S.AutomationEnableViaShizuku] else s[S.AutomationOpenA11ySettings],
                    )
                }
            },
            dismissButton = {
                Row {
                    if (shizukuAvailable) {
                        TextButton(
                            onClick = {
                                showEnablePrompt = false
                                context.startActivity(backend.openAccessibilitySettingsIntent())
                            },
                        ) {
                            Text(s[S.AutomationOpenA11ySettings])
                        }
                    }
                    TextButton(onClick = { showEnablePrompt = false }) {
                        Text(s[S.Close])
                    }
                }
            },
        )
    }

    // 新增訂閱 URL 彈窗
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(s[S.AutomationAddSubscription]) },
            text = {
                OutlinedTextField(
                    value = addUrlText,
                    onValueChange = { addUrlText = it },
                    label = { Text(s[S.AutomationAddSubUrlHint]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = addUrlText.isNotBlank() && !busy,
                    onClick = {
                        val url = addUrlText.trim()
                        showAddDialog = false
                        scope.launch {
                            busy = true
                            val res = backend.importAutomationSubscription(url)
                            busy = false
                            when (res) {
                                is SubscriptionFetcher.FetchResult.Success ->
                                    toast(s.format(S.AutomationSubImported, res.parsed.subscription.name))
                                is SubscriptionFetcher.FetchResult.UpToDate ->
                                    toast(s[S.AutomationSubUpToDate])
                                is SubscriptionFetcher.FetchResult.Error ->
                                    toast(s.format(S.AutomationSubFailed, res.message))
                            }
                            refreshData()
                        }
                    },
                ) {
                    Text(s[S.Add])
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(s[S.Cancel]) }
            },
        )
    }

    // 刪除訂閱確認
    deletingSub?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingSub = null },
            title = { Text(s[S.Delete]) },
            text = { Text(s.format(S.AutomationDeleteSubConfirm, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        backend.deleteAutomationSubscription(target.id)
                        deletingSub = null
                        refreshData()
                    },
                ) {
                    Text(s[S.Delete], color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSub = null }) { Text(s[S.Cancel]) }
            },
        )
    }

    // 規則組開關管理彈窗
    managingSub?.let { target ->
        SubscriptionGroupsDialog(
            backend = backend,
            record = target,
            onDismiss = { managingSub = null },
        )
    }
}

@Composable
private fun SubscriptionGroupsDialog(
    backend: AppBackend,
    record: SubscriptionRecord,
    onDismiss: () -> Unit,
) {
    val s = rememberUiStrings()
    var rawSub by remember(record.id) { mutableStateOf<RawSubscription?>(null) }
    var overrides by remember(record.id) { mutableStateOf<Map<Pair<String, Int>, Boolean>>(emptyMap()) }

    LaunchedEffect(record.id) {
        val (loaded, ovList) = withContext(Dispatchers.IO) {
            backend.loadAutomationSubscriptionDetail(record.id) to backend.listAutomationGroupOverrides(record.id)
        }
        rawSub = loaded
        overrides = ovList.associate { (it.appId to it.groupKey) to it.enabled }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            val sub = rawSub
            if (sub == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (sub.globalGroups.isNotEmpty()) {
                        item {
                            Text(
                                s[S.AutomationGlobalGroups],
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(sub.globalGroups, key = { "g:${it.key}" }) { group ->
                            val enabled = overrides["" to group.key] ?: (group.enable ?: true)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { checked ->
                                        overrides = overrides + (("" to group.key) to checked)
                                        backend.setAutomationGroupOverride(record.id, "", group.key, checked)
                                    },
                                )
                            }
                        }
                    }
                    sub.apps.forEach { app ->
                        item(key = "app:${app.id}") {
                            Text(
                                app.name?.takeIf { it.isNotBlank() }?.let { "$it (${app.id})" } ?: app.id,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        items(app.groups, key = { "${app.id}:${it.key}" }) { group ->
                            val enabled = overrides[app.id to group.key] ?: (group.enable ?: true)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { checked ->
                                        overrides = overrides + ((app.id to group.key) to checked)
                                        backend.setAutomationGroupOverride(record.id, app.id, group.key, checked)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s[S.Close]) }
        },
    )
}
