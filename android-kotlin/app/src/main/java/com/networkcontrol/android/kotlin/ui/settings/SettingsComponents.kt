package com.networkcontrol.android.kotlin.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.networkcontrol.android.kotlin.ui.theme.LocalAppVisualTokens

/**
 * 設定分組卡片：上方放置精煉分組標題，內部由圓角卡片包裹多個設定項並以細分隔線區隔。
 */
@Composable
internal fun SettingsGroup(
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
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 7.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = container,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            shape = RoundedCornerShape(tokens.cardCornerRadius),
            border = border,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
internal fun SettingsDivider(insetWithIcon: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (insetWithIcon) 62.dp else 16.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
internal fun SettingsIconBadge(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppVisualTokens.current
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = if (tokens.isDarkCanvas) 0.18f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun SettingsDrawableBadge(
    @DrawableRes drawableRes: Int,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppVisualTokens.current
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = if (tokens.isDarkCanvas) 0.18f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(drawableRes),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun SettingsStatusPill(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppVisualTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = if (tokens.isDarkCanvas) 0.20f else 0.12f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * 標準導覽 / 動作列：左側圓角圖示徽章、中間標題與摘要、右側狀態標籤與箭頭。
 */
@Composable
internal fun SettingsTile(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    @DrawableRes drawableIcon: Int? = null,
    iconAccent: Color = MaterialTheme.colorScheme.primary,
    subtitleHighlighted: Boolean = false,
    badgeText: String? = null,
    badgeAccent: Color = MaterialTheme.colorScheme.primary,
    trailingText: String? = null,
    showChevron: Boolean = true,
    enabled: Boolean = true,
    leadingCustom: (@Composable () -> Unit)? = null,
    trailingCustom: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leadingCustom != null -> {
                leadingCustom()
                Spacer(Modifier.width(12.dp))
            }
            icon != null -> {
                SettingsIconBadge(icon = icon, accent = iconAccent.copy(alpha = alpha))
                Spacer(Modifier.width(12.dp))
            }
            drawableIcon != null -> {
                SettingsDrawableBadge(drawableRes = drawableIcon, accent = iconAccent.copy(alpha = alpha))
                Spacer(Modifier.width(12.dp))
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subtitleHighlighted) {
                        MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (badgeText != null) {
            Spacer(Modifier.width(8.dp))
            SettingsStatusPill(text = badgeText, accent = badgeAccent)
        }
        if (trailingText != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (trailingCustom != null) {
            Spacer(Modifier.width(8.dp))
            trailingCustom()
        }
        if (showChevron && onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 標準開關列：支援可選圖示徽章與垂直置中對齊。
 */
@Composable
internal fun SettingsSwitchTile(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    @DrawableRes drawableIcon: Int? = null,
    iconAccent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.clickable { onCheckedChange(!checked) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            icon != null -> {
                SettingsIconBadge(icon = icon, accent = iconAccent.copy(alpha = alpha))
                Spacer(Modifier.width(12.dp))
            }
            drawableIcon != null -> {
                SettingsDrawableBadge(drawableRes = drawableIcon, accent = iconAccent.copy(alpha = alpha))
                Spacer(Modifier.width(12.dp))
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * 分段選擇列：上方標題與目前值左右水平置中對齊，下方全寬等分膠囊條，
 * 避免窄螢幕下 FilterChip 換行變形與基線錯位。
 */
@Composable
internal fun <T> SettingsSegmentedRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    currentBadge: String? = null,
) {
    val tokens = LocalAppVisualTokens.current
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (currentBadge != null) {
                Spacer(Modifier.width(8.dp))
                SettingsStatusPill(text = currentBadge, accent = scheme.primary)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (tokens.isDarkCanvas) {
                        scheme.surfaceVariant.copy(alpha = 0.55f)
                    } else {
                        scheme.surfaceVariant.copy(alpha = 0.72f)
                    },
                )
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (isSelected) {
                                scheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                        )
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = scheme.primary.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(9.dp),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 權限狀態列：左側紅綠燈圓點 + 標題與狀態 + 右側操作膠囊按鈕。
 */
@Composable
internal fun SettingsPermissionTile(
    title: String,
    statusText: String,
    ok: Boolean,
    actionText: String?,
    onAction: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dotColor = if (ok) scheme.primary else scheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!ok && actionText != null) Modifier.clickable(onClick = onAction) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) scheme.onSurfaceVariant else scheme.error,
            )
        }
        if (actionText != null) {
            Spacer(Modifier.width(8.dp))
            SettingsStatusPill(
                text = actionText,
                accent = scheme.primary,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

/**
 * 設定二級頁統一框架：返回按鈕 + 標題 + 捲動內容區。
 */
@Composable
internal fun SettingsSubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
