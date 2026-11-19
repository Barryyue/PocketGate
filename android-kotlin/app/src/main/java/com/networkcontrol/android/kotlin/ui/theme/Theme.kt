package com.networkcontrol.android.kotlin.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.networkcontrol.android.kotlin.core.model.AppThemeStyle

/** 預設品牌色（目前的綠色）。 */
val DefaultThemeColor = Color(0xFF2E7D32)

/** 各視覺主題風格的擴充外觀令牌：控制卡片底色、邊框光澤、圓角與環境光暈。 */
@Immutable
data class AppVisualTokens(
    val style: AppThemeStyle = AppThemeStyle.Classic,
    val cardContainer: Color = Color.Unspecified,
    val cardBorder: Color = Color.Transparent,
    val cardCornerRadius: Dp = 20.dp,
    val metricCornerRadius: Dp = 18.dp,
    val auroraPrimary: Color = Color.Transparent,
    val auroraSecondary: Color = Color.Transparent,
    val isDarkCanvas: Boolean = false,
)

val LocalAppVisualTokens = staticCompositionLocalOf { AppVisualTokens() }

@Composable
fun NetworkControlTheme(
    seedColor: Color = DefaultThemeColor,
    themeStyle: AppThemeStyle = AppThemeStyle.Classic,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val effectiveDark = when (themeStyle) {
        AppThemeStyle.ObsidianCyber, AppThemeStyle.AmoledBlack -> true
        AppThemeStyle.FrostMinimal -> false
        AppThemeStyle.Classic, AppThemeStyle.AuroraGlass, AppThemeStyle.DynamicYou -> darkTheme
    }

    val colorScheme = remember(seedColor, themeStyle, effectiveDark) {
        buildColorScheme(context, seedColor, themeStyle, effectiveDark)
    }
    val visualTokens = remember(colorScheme, seedColor, themeStyle, effectiveDark) {
        buildVisualTokens(colorScheme, seedColor, themeStyle, effectiveDark)
    }

    CompositionLocalProvider(LocalAppVisualTokens provides visualTokens) {
        MaterialTheme(
            colorScheme = colorScheme,
        ) {
            CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
                content()
            }
        }
    }
}

private fun buildColorScheme(
    context: android.content.Context,
    seed: Color,
    style: AppThemeStyle,
    dark: Boolean,
): ColorScheme = when (style) {
    AppThemeStyle.ObsidianCyber -> obsidianCyberScheme(seed)
    AppThemeStyle.FrostMinimal -> frostMinimalScheme(seed)
    AppThemeStyle.AuroraGlass -> if (dark) auroraDarkScheme(seed) else auroraLightScheme(seed)
    AppThemeStyle.AmoledBlack -> amoledBlackScheme(seed)
    AppThemeStyle.DynamicYou -> if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkScheme(seed) else lightScheme(seed)
    }
    AppThemeStyle.Classic -> if (dark) darkScheme(seed) else lightScheme(seed)
}

private fun buildVisualTokens(
    scheme: ColorScheme,
    seed: Color,
    style: AppThemeStyle,
    dark: Boolean,
): AppVisualTokens = when (style) {
    AppThemeStyle.ObsidianCyber -> AppVisualTokens(
        style = style,
        cardContainer = Color(0xFF12161D),
        cardBorder = Color.White.copy(alpha = 0.09f),
        cardCornerRadius = 20.dp,
        metricCornerRadius = 16.dp,
        auroraPrimary = Color(0xFF4ADE80).copy(alpha = 0.14f),
        auroraSecondary = Color(0xFFC9F45A).copy(alpha = 0.09f),
        isDarkCanvas = true,
    )
    AppThemeStyle.FrostMinimal -> AppVisualTokens(
        style = style,
        cardContainer = Color.White,
        cardBorder = Color(0xFFE2E8F0),
        cardCornerRadius = 20.dp,
        metricCornerRadius = 16.dp,
        auroraPrimary = Color.Transparent,
        auroraSecondary = Color.Transparent,
        isDarkCanvas = false,
    )
    AppThemeStyle.AuroraGlass -> AppVisualTokens(
        style = style,
        cardContainer = if (dark) Color(0xFF151E24).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.78f),
        cardBorder = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.85f),
        cardCornerRadius = 22.dp,
        metricCornerRadius = 18.dp,
        auroraPrimary = lerp(seed, Color(0xFF34D399), 0.35f).copy(alpha = if (dark) 0.24f else 0.18f),
        auroraSecondary = Color(0xFF38BDF8).copy(alpha = if (dark) 0.18f else 0.14f),
        isDarkCanvas = dark,
    )
    AppThemeStyle.AmoledBlack -> AppVisualTokens(
        style = style,
        cardContainer = Color(0xFF0A0A0A),
        cardBorder = Color.White.copy(alpha = 0.12f),
        cardCornerRadius = 18.dp,
        metricCornerRadius = 16.dp,
        auroraPrimary = Color.Transparent,
        auroraSecondary = Color.Transparent,
        isDarkCanvas = true,
    )
    AppThemeStyle.DynamicYou, AppThemeStyle.Classic -> AppVisualTokens(
        style = style,
        cardContainer = scheme.surfaceVariant.copy(alpha = 0.45f),
        cardBorder = Color.Transparent,
        cardCornerRadius = 20.dp,
        metricCornerRadius = 18.dp,
        auroraPrimary = Color.Transparent,
        auroraSecondary = Color.Transparent,
        isDarkCanvas = dark,
    )
}

/** 黑曜石極客：呼應官網落地頁與新 Logo 的炭黑 + 熒光綠高對比配色。 */
private fun obsidianCyberScheme(seed: Color): ColorScheme {
    val accent = if (seed == DefaultThemeColor) Color(0xFFC9F45A) else lerp(seed, Color.White, 0.45f)
    return darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF0A0D0B),
        primaryContainer = lerp(accent, Color(0xFF0A0D0B), 0.78f),
        onPrimaryContainer = Color(0xFFECFDF5),
        secondary = Color(0xFF4ADE80),
        onSecondary = Color(0xFF06150C),
        secondaryContainer = Color(0xFF15291E),
        onSecondaryContainer = Color(0xFFD1FAE5),
        tertiary = Color(0xFF8FB4E8),
        onTertiary = Color(0xFF0B1420),
        tertiaryContainer = Color(0xFF172230),
        onTertiaryContainer = Color(0xFFDCEBFF),
        background = Color(0xFF08090B),
        onBackground = Color(0xFFF2F3F4),
        surface = Color(0xFF0C0E11),
        onSurface = Color(0xFFF2F3F4),
        surfaceVariant = Color(0xFF171B22),
        onSurfaceVariant = Color(0xFF9AA1AB),
        outline = Color(0xFF48505C),
        outlineVariant = Color(0xFF232832),
        error = Color(0xFFFF6A55),
        onError = Color(0xFF280603),
        errorContainer = Color(0xFF3E1512),
        onErrorContainer = Color(0xFFFFDAD6),
    )
}

/** 極簡霜白：清爽米白/淺灰畫布 + 純白卡片 + 銳利文字對比。 */
private fun frostMinimalScheme(seed: Color): ColorScheme = lightColorScheme(
    primary = seed,
    onPrimary = onColor(seed),
    primaryContainer = lerp(seed, Color.White, 0.86f),
    onPrimaryContainer = lerp(seed, Color.Black, 0.70f),
    secondary = Color(0xFF475569),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0F2FE),
    onTertiaryContainer = Color(0xFF0C4A6E),
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF4F6F9),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEAEFF5),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

/** 極光流光（淺色） */
private fun auroraLightScheme(seed: Color): ColorScheme = lightScheme(seed).copy(
    background = Color(0xFFF0F5F8),
    surface = Color(0xFFF0F5F8),
    surfaceVariant = Color(0xFFE1EDF3),
    outlineVariant = Color(0xFFC5D7E2),
)

/** 極光流光（深色） */
private fun auroraDarkScheme(seed: Color): ColorScheme = darkScheme(seed).copy(
    background = Color(0xFF0A1014),
    surface = Color(0xFF0E161B),
    surfaceVariant = Color(0xFF1C2932),
    outlineVariant = Color(0xFF283A46),
)

/** 純黑省電（AMOLED） */
private fun amoledBlackScheme(seed: Color): ColorScheme = darkScheme(seed).copy(
    background = Color.Black,
    onBackground = Color(0xFFF5F5F5),
    surface = Color.Black,
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFFA8B0B9),
    outlineVariant = Color(0xFF262626),
)

private fun lightScheme(seed: Color) = lightColorScheme(
    primary = seed,
    onPrimary = onColor(seed),
    primaryContainer = lerp(seed, Color.White, 0.78f),
    onPrimaryContainer = lerp(seed, Color.Black, 0.68f),
    secondary = lerp(seed, Color(0xFF5B5B5B), 0.55f),
    onSecondary = Color.White,
    secondaryContainer = lerp(seed, Color.White, 0.86f),
    onSecondaryContainer = lerp(seed, Color.Black, 0.66f),
    tertiary = lerp(seed, Color(0xFF3B6FA0), 0.5f),
    onTertiary = Color.White,
    tertiaryContainer = lerp(seed, Color.White, 0.86f),
    onTertiaryContainer = lerp(seed, Color.Black, 0.66f),
    background = lerp(seed, Color.White, 0.965f),
    onBackground = lerp(seed, Color.Black, 0.87f),
    surface = lerp(seed, Color.White, 0.975f),
    onSurface = lerp(seed, Color.Black, 0.84f),
    surfaceVariant = lerp(seed, Color.White, 0.91f),
    onSurfaceVariant = lerp(seed, Color.Black, 0.62f),
    outline = lerp(seed, Color.Black, 0.55f),
    outlineVariant = lerp(seed, Color.White, 0.75f),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private fun darkScheme(seed: Color) = darkColorScheme(
    primary = lerp(seed, Color.White, 0.62f),
    onPrimary = lerp(seed, Color.Black, 0.78f),
    primaryContainer = lerp(seed, Color.Black, 0.42f),
    onPrimaryContainer = lerp(seed, Color.White, 0.82f),
    secondary = lerp(seed, Color.White, 0.55f),
    onSecondary = lerp(seed, Color.Black, 0.75f),
    secondaryContainer = lerp(seed, Color.Black, 0.45f),
    onSecondaryContainer = lerp(seed, Color.White, 0.82f),
    tertiary = lerp(seed, Color(0xFF9CCBFF), 0.55f),
    onTertiary = lerp(seed, Color.Black, 0.75f),
    tertiaryContainer = lerp(seed, Color.Black, 0.45f),
    onTertiaryContainer = lerp(seed, Color.White, 0.82f),
    background = lerp(seed, Color(0xFF11140F), 0.9f),
    onBackground = lerp(seed, Color.White, 0.86f),
    surface = lerp(seed, Color(0xFF11140F), 0.92f),
    onSurface = lerp(seed, Color.White, 0.84f),
    surfaceVariant = lerp(seed, Color(0xFF424940), 0.72f),
    onSurfaceVariant = lerp(seed, Color.White, 0.68f),
    outline = lerp(seed, Color.White, 0.55f),
    outlineVariant = lerp(seed, Color(0xFF424940), 0.55f),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private fun onColor(seed: Color): Color =
    if (seed.luminance() > 0.58f) Color(0xFF1B1B1B) else Color.White
