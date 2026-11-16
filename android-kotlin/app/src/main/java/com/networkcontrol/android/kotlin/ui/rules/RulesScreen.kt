package com.networkcontrol.android.kotlin.ui.rules

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleGroup
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.AppIcon
import com.networkcontrol.android.kotlin.ui.common.ScreenHeader
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.launch

@Composable
fun RulesScreen(backend: AppBackend) {
    val s = rememberUiStrings()
    val scope = rememberCoroutineScope()
    val ruleSet by backend.container.rules.ruleSet.collectAsStateWithLifecycle()
    val sims = remember { runCatching { backend.subscriptions() }.getOrDefault(emptyList()) }
    val groups = remember(ruleSet.version) { backend.ruleGroups() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader(s[S.Rules], s[S.RulesSubtitle]) }
        if (groups.isEmpty()) {
            item {
                Text(
                    s[S.NoRules],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(groups, key = { it.key }) { group ->
            RuleGroupCard(
                backend = backend,
                group = group,
                sims = sims,
                onChanged = { },
                scope = scope,
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun RuleGroupCard(
    backend: AppBackend,
    group: RuleGroup,
    sims: List<com.networkcontrol.android.kotlin.core.model.SimInfo>,
    onChanged: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val s = rememberUiStrings()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (group.packageName != null) {
                    AppIcon(group.packageName, Modifier.width(36.dp).height(36.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.packageName?.let { group.label } ?: s[S.GlobalRule],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    group.packageName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "${group.rules.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            group.rules.forEach { rule ->
                RuleRow(
                    rule = rule,
                    sims = sims,
                    onToggle = { enabled ->
                        scope.launch {
                            backend.setRuleEnabled(rule.id, enabled)
                            onChanged()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            backend.deleteRule(rule.id)
                            onChanged()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: Rule,
    sims: List<com.networkcontrol.android.kotlin.core.model.SimInfo>,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val s = rememberUiStrings()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${actionLabel(rule.action, s)} · ${networkScopeLabel(rule.conditions, sims, s)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                buildString {
                    append(rule.lifetime.name)
                    append(" · ")
                    append(if (rule.profileId == null) s[S.GlobalRule] else s[S.ActiveProfile])
                    if (rule.hitCount > 0) {
                        append(" · ${rule.hitCount}×")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        if (rule.source != RuleSource.Builtin) {
            TextButton(onClick = onDelete) { Text(s[S.Delete]) }
        }
    }
}

private fun actionLabel(
    action: VerdictAction,
    s: com.networkcontrol.android.kotlin.ui.UiStrings,
): String = if (action == VerdictAction.Allow) s[S.Allow] else s[S.Deny]

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
