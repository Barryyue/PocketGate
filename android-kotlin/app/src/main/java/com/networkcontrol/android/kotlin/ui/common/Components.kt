package com.networkcontrol.android.kotlin.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.core.model.VerdictReason
import com.networkcontrol.android.kotlin.engine.apps.AppCatalog
import com.networkcontrol.android.kotlin.platform.AppIconLoader
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings
import com.networkcontrol.android.kotlin.ui.theme.LocalAppVisualTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalAppVisualTokens.current
    val container = if (tokens.cardContainer != Color.Unspecified) {
        tokens.cardContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val border = if (tokens.cardBorder != Color.Transparent) {
        BorderStroke(1.dp, tokens.cardBorder)
    } else {
        null
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(tokens.cardCornerRadius),
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

/**
 * 雙列 Bento 儀表盤瓷磚卡片：左上圖示徽章 + 短標題 + 核心等寬數值 + 輔助說明，
 * 支援強調態邊框與點擊互動。
 */
@Composable
fun BentoTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val tokens = LocalAppVisualTokens.current
    val scheme = MaterialTheme.colorScheme
    val container = if (tokens.cardContainer != Color.Unspecified) {
        tokens.cardContainer
    } else {
        scheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val borderColor = when {
        highlighted -> accent.copy(alpha = if (tokens.isDarkCanvas) 0.48f else 0.40f)
        tokens.cardBorder != Color.Transparent -> tokens.cardBorder
        else -> Color.Transparent
    }
    val shape = RoundedCornerShape(tokens.metricCornerRadius)
    Card(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = scheme.onSurface,
        ),
        shape = shape,
        border = if (borderColor != Color.Transparent) BorderStroke(1.dp, borderColor) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(accent.copy(alpha = if (tokens.isDarkCanvas) 0.18f else 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (highlighted) accent else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) { AppIconLoader.load(context, packageName) }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp))
        } else {
            Icon(
                painterResource(com.networkcontrol.android.kotlin.R.drawable.ic_shield),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} B" else "%.1f %s".format(value, units[index])
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

fun formatTime(epochMillis: Long): String =
    timeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

fun formatDateTime(epochMillis: Long): String =
    dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

fun trackerCategoryLabel(s: UiStrings, category: String): String = when (category) {
    "Advertising" -> s[S.TrackerCatAdvertising]
    "Analytics" -> s[S.TrackerCatAnalytics]
    "Social" -> s[S.TrackerCatSocial]
    "Fingerprinting" -> s[S.TrackerCatFingerprinting]
    "Cryptomining" -> s[S.TrackerCatCryptomining]
    "Email" -> s[S.TrackerCatEmail]
    "Content" -> s[S.TrackerCatContent]
    "Anti-fraud" -> s[S.TrackerCatAntiFraud]
    "ConsentManagers" -> s[S.TrackerCatConsent]
    else -> category
}

fun reasonLabel(s: UiStrings, reason: VerdictReason): String = when (reason) {
    VerdictReason.Rule -> s[S.ReasonRule]
    VerdictReason.Mode -> s[S.ReasonMode]
    VerdictReason.Prompt -> s[S.ReasonPrompt]
    VerdictReason.PromptTimeout -> s[S.ReasonTimeout]
    VerdictReason.DoNotDisturb -> s[S.ReasonDnd]
    VerdictReason.Builtin -> s[S.ReasonBuiltin]
    VerdictReason.FailSafe -> s[S.ReasonFailSafe]
    VerdictReason.NoUserSession -> s[S.ReasonMode]
    VerdictReason.Cancelled -> s[S.ReasonFailSafe]
    VerdictReason.Blocklist -> s[S.ReasonBlocklist]
}

fun protocolLabel(protocol: L4Protocol): String = when (protocol) {
    L4Protocol.Tcp -> "TCP"
    L4Protocol.Udp -> "UDP"
    L4Protocol.Icmp -> "ICMP"
    L4Protocol.IcmpV6 -> "ICMPv6"
    L4Protocol.Other -> "Other"
}

fun appIdentityPath(packageName: String): String = AppCatalog.appPath(packageName)
