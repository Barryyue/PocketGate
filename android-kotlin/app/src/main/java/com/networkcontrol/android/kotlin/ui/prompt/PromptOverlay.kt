package com.networkcontrol.android.kotlin.ui.prompt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.AppIcon
import com.networkcontrol.android.kotlin.ui.common.protocolLabel
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.delay

internal fun promptDisplayName(path: String?): String? = when {
    path == null -> null
    path.startsWith("android-app://uid:") -> "UID " + path.substringAfter("android-app://uid:")
    path.startsWith("android-app://") -> path.removePrefix("android-app://")
    else -> path
}

@Composable
fun PromptOverlay(backend: AppBackend, modifier: Modifier = Modifier) {
    val s = rememberUiStrings()
    val prompts by backend.prompts.collectAsStateWithLifecycle()
    val settings by backend.settings.collectAsStateWithLifecycle()
    val prompt = prompts.firstOrNull() ?: return
    val prefs = backend.container.preferences
    val timedMinutes = settings.promptTimedMinutes

    LaunchedEffect(prompt.id) {
        while (true) {
            backend.holdPrompt(prompt.id)
            delay(10_000)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val packageName = prompt.app?.path?.removePrefix("android-app://")
                    ?: prompt.executablePath?.removePrefix("android-app://")
                    ?: ""
                AppIcon(packageName, Modifier.width(40.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val appLabel = prompt.app?.displayName
                        ?: prompt.app?.name
                        ?: promptDisplayName(prompt.executablePath)
                        ?: s[S.UnknownApp]
                    Text(
                        s.format(S.PromptTitle, appLabel),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val first = prompt.flows.firstOrNull()
                    val target = first?.let {
                        it.domains.firstOrNull() ?: "${it.remote.address.hostAddress}:${it.remote.port}"
                    } ?: ""
                    if (prefs.promptShowDetails) {
                        Text(
                            "${s[S.PromptTarget]}：$target · ${first?.let { protocolLabel(it.protocol) }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (prompt.flows.size > 1) {
                            Text(
                                s.format(S.PromptFlows, prompt.flows.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (prefs.promptShowExplanation) {
                Text(
                    s[S.PromptBlockedHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (prompt.isTamperAlert) {
                Text(
                    s[S.PromptTamper],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        backend.answerPrompt(
                            prompt.id,
                            VerdictAction.Deny,
                            RuleLifetime.Permanent,
                            null,
                        )
                    },
                ) { Text(s[S.Deny]) }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        backend.answerPrompt(
                            prompt.id,
                            VerdictAction.Allow,
                            RuleLifetime.UntilAppExit,
                            null,
                        )
                    },
                ) { Text(s[S.PromptOnce]) }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        backend.answerPrompt(
                            prompt.id,
                            VerdictAction.Allow,
                            RuleLifetime.Permanent,
                            null,
                        )
                    },
                ) { Text(s[S.Allow]) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { backend.dismissPrompt(prompt.id) }) {
                    Text(s[S.PromptLater])
                }
                Spacer(Modifier.weight(1f))
                if (prefs.promptShowTimedOption) {
                    TextButton(
                        onClick = {
                            backend.answerPrompt(
                                prompt.id,
                                VerdictAction.Allow,
                                RuleLifetime.Timed,
                                timedMinutes * 60,
                            )
                        },
                    ) {
                        Text(s.format(S.PromptTimed, timedMinutes))
                    }
                }
            }
        }
    }
}
