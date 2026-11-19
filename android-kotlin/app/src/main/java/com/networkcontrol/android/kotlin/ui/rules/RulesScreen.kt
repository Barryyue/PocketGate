package com.networkcontrol.android.kotlin.ui.rules

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.networkcontrol.android.kotlin.ui.theme.LocalAppVisualTokens
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 20.dp + com.networkcontrol.android.kotlin.ui.LocalBottomContentPadding.current,
        ),
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
    val guard = com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction.current
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
    Card(
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = scheme.onSurface,
        ),
        shape = RoundedCornerShape(tokens.cardCornerRadius),
        border = border,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (group.packageName != null) {
                    AppIcon(group.packageName, Modifier.width(38.dp).height(38.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.packageName?.let { group.label } ?: s[S.GlobalRule],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    group.packageName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(scheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text(
                        "${group.rules.size}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.primary,
                    )
                }
            }
            group.rules.forEach { rule ->
                RuleRow(
                    rule = rule,
                    sims = sims,
                    onToggle = { enabled ->
                        guard.run {
                            scope.launch {
                                backend.setRuleEnabled(rule.id, enabled)
                                onChanged()
                            }
                        }
                    },
                    onDelete = {
                        guard.run {
                            scope.launch {
                                backend.deleteRule(rule.id)
                                onChanged()
                            }
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
    val scheme = MaterialTheme.colorScheme
    val accent = if (rule.action == VerdictAction.Allow) scheme.primary else scheme.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(if (rule.enabled) accent else scheme.outlineVariant),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    actionLabel(rule.action, s),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Text(
                    "· ${networkScopeLabel(rule.conditions, sims, s)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                buildString {
                    append(rule.lifetime.name)
                    append(" · ")
                    append(if (rule.profileId == null) s[S.GlobalRule] else s[S.ActiveProfile])
                    if (rule.hitCount > 0) {
                        append(" · ${rule.hitCount}×")
                    }
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = scheme.onSurfaceVariant,
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
