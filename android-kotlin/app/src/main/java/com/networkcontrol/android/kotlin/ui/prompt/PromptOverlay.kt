package com.networkcontrol.android.kotlin.ui.prompt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.platform.prompt.PromptDenyPolicy
import com.networkcontrol.android.kotlin.platform.prompt.PromptSheetText
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.protocolLabel
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import kotlinx.coroutines.delay

internal fun promptDisplayName(path: String?): String? = when {
    path == null -> null
    path.startsWith("android-app://uid:") -> "UID " + path.substringAfter("android-app://uid:")
    path.startsWith("android-app://") -> path.removePrefix("android-app://")
    else -> path
}

/** 應用內的詢問卡片，樣式與系統懸浮窗一致（仿紅魔：一句說明、「禁止後不再提示」、禁止 | 允許 | 僅一次）。 */
@Composable
fun PromptOverlay(backend: AppBackend, modifier: Modifier = Modifier) {
    val s = rememberUiStrings()
    val prompts by backend.prompts.collectAsStateWithLifecycle()
    val settings by backend.settings.collectAsStateWithLifecycle()
    val network by backend.container.network.state.collectAsStateWithLifecycle()
    val prompt = prompts.firstOrNull() ?: return
    val prefs = backend.container.preferences
    val timedMinutes = settings.promptTimedMinutes
    val denyPermanent = prefs.promptDenyPermanent
    var dontAskAgain by remember(prompt.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val ownName = remember { context.applicationInfo.loadLabel(context.packageManager).toString() }

    LaunchedEffect(prompt.id) {
        while (true) {
            backend.holdPrompt(prompt.id)
            delay(10_000)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 6.dp)) {
            val appLabel = prompt.app?.displayName
                ?: prompt.app?.name
                ?: promptDisplayName(prompt.executablePath)
                ?: s[S.UnknownApp]
            Text(
                PromptSheetText.message(s, ownName, appLabel, network.kind),
                fontSize = 18.sp,
                lineHeight = 26.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (prefs.promptShowDetails) {
                val first = prompt.flows.firstOrNull()
                val target = first?.let {
                    it.domains.firstOrNull() ?: "${it.remote.address.hostAddress}:${it.remote.port}"
                }.orEmpty()
                val more = if (prompt.flows.size > 1) " · " + s.format(S.PromptFlows, prompt.flows.size) else ""
                Text(
                    "${s[S.PromptTarget]}：$target · ${first?.let { protocolLabel(it.protocol) }.orEmpty()}$more",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (prompt.isTamperAlert) {
                Text(
                    s[S.PromptTamper],
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!denyPermanent) {
                // 勾選後「禁止」改為永久禁止；不勾只禁止到應用關閉。
                Row(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { dontAskAgain = !dontAskAgain },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = dontAskAgain, onClick = { dontAskAgain = !dontAskAgain })
                    Text(s[S.PromptDontAskAgain], fontSize = 16.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetAction(s[S.PromptDeny]) {
                    backend.answerPrompt(
                        prompt.id,
                        VerdictAction.Deny,
                        PromptDenyPolicy.lifetime(dontAskAgain, denyPermanent),
                        null,
                    )
                }
                SheetDivider()
                SheetAction(s[S.Allow]) {
                    backend.answerPrompt(prompt.id, VerdictAction.Allow, RuleLifetime.Permanent, null)
                }
                SheetDivider()
                SheetAction(s[S.PromptOnce]) {
                    backend.answerPrompt(prompt.id, VerdictAction.Allow, RuleLifetime.UntilAppExit, null)
                }
                if (prefs.promptShowTimedOption) {
                    SheetDivider()
                    SheetAction(s.format(S.PromptTimedShort, timedMinutes)) {
                        backend.answerPrompt(prompt.id, VerdictAction.Allow, RuleLifetime.Timed, timedMinutes * 60)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SheetAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Text(label, fontSize = 17.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SheetDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(22.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
