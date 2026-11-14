package com.networkcontrol.android.kotlin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** 預設品牌色（目前的綠色）。 */
val DefaultThemeColor = Color(0xFF2E7D32)

@Composable
fun NetworkControlTheme(
    seedColor: Color = DefaultThemeColor,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val light = remember(seedColor) { lightScheme(seedColor) }
    val dark = remember(seedColor) { darkScheme(seedColor) }
    MaterialTheme(
        colorScheme = if (darkTheme) dark else light,
        content = content,
    )
}

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
