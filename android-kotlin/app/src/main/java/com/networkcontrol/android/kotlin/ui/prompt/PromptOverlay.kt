package com.networkcontrol.android.kotlin.ui.prompt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.model.PromptSnapshot
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.platform.prompt.PromptDenyPolicy
import com.networkcontrol.android.kotlin.platform.prompt.PromptSheetText
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

internal fun promptPackageName(prompt: PromptSnapshot): String? {
    val raw = prompt.app?.path ?: prompt.executablePath ?: return null
    if (!raw.startsWith("android-app://") || raw.startsWith("android-app://uid:")) return null
    return raw.removePrefix("android-app://").takeIf { it.isNotBlank() }
}

/** 應用內的詢問卡片，樣式與系統懸浮窗一致（頂部應用圖示 + 突出應用名稱與網路標籤 + 主題色高亮文案）。 */
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
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 6.dp)) {
            val appLabel = prompt.app?.displayName
                ?: prompt.app?.name
                ?: promptDisplayName(prompt.executablePath)
                ?: s[S.UnknownApp]
            val pkgName = promptPackageName(prompt)
            val networkLabel = when (network.kind) {
                NetworkKind.Wifi -> s[S.PromptNetWlan]
                NetworkKind.Cellular -> s[S.PromptNetMobile]
                else -> s[S.PromptNetAny]
            }

            // 頂部應用識別列：圓角應用圖示 + 加粗突出應用名稱與包名 + 網路類型膠囊標籤
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pkgName != null) {
                    AppIcon(packageName = pkgName, modifier = Modifier.size(46.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_shield),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        appLabel,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitleText = pkgName ?: promptDisplayName(prompt.executablePath) ?: ""
                    if (subtitleText.isNotBlank() && subtitleText != appLabel) {
                        Text(
                            subtitleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (prompts.size > 1) {
                    Text(
                        "1 / ${prompts.size}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    networkLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            val rawMessage = PromptSheetText.message(s, ownName, appLabel, network.kind)
            val primaryColor = MaterialTheme.colorScheme.primary
            val annotatedMessage = remember(rawMessage, appLabel, ownName, primaryColor) {
                buildAnnotatedString {
                    append(rawMessage)
                    val start = rawMessage.indexOf(appLabel, startIndex = ownName.length)
                    if (start >= 0) {
                        addStyle(
                            SpanStyle(
                                color = primaryColor,
                                fontWeight = FontWeight.Bold,
                            ),
                            start = start,
                            end = start + appLabel.length,
                        )
                    }
                }
            }
            Text(
                annotatedMessage,
                modifier = Modifier.padding(top = 12.dp),
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (prefs.promptShowDetails) {
                val first = prompt.flows.firstOrNull()
                if (first != null) {
                    val target = first.domains.firstOrNull()
                        ?: "${first.remote.address.hostAddress}:${first.remote.port}"
                    val more = if (prompt.flows.size > 1) " · " + s.format(S.PromptFlows, prompt.flows.size) else ""
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "${s[S.PromptTarget]}：$target · ${protocolLabel(first.protocol)}$more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (prompt.isTamperAlert) {
                Text(
                    s[S.PromptTamper],
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!denyPermanent) {
                // 勾選後「禁止」改為永久禁止；不勾只禁止到應用關閉。
                Row(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .toggleable(
                            value = dontAskAgain,
                            role = Role.Checkbox,
                            onValueChange = { dontAskAgain = it },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(s[S.PromptDontAskAgain], fontSize = 15.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val allowEnabled = !dontAskAgain
                SheetAction(s[S.PromptDeny], color = MaterialTheme.colorScheme.error) {
                    backend.answerPrompt(
                        prompt.id,
                        VerdictAction.Deny,
                        PromptDenyPolicy.lifetime(dontAskAgain, denyPermanent),
                        null,
                    )
                }
                SheetDivider()
                SheetAction(s[S.Allow], enabled = allowEnabled) {
                    backend.answerPrompt(prompt.id, VerdictAction.Allow, RuleLifetime.Permanent, null)
                }
                SheetDivider()
                SheetAction(s[S.PromptOnce], enabled = allowEnabled) {
                    backend.answerPrompt(prompt.id, VerdictAction.Allow, RuleLifetime.UntilAppExit, null)
                }
                if (prefs.promptShowTimedOption) {
                    SheetDivider()
                    SheetAction(s.format(S.PromptTimedShort, timedMinutes), enabled = allowEnabled) {
                        backend.answerPrompt(prompt.id, VerdictAction.Allow, RuleLifetime.Timed, timedMinutes * 60)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SheetAction(
    label: String,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val textColor = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        // 一律單行；四個按鈕或大字體放不下時自動縮小，不換行。
        BasicText(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = textColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 11.sp,
                maxFontSize = 17.sp,
            ),
        )
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
