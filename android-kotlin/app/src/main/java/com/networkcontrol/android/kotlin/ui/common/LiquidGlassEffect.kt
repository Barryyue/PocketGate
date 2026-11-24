package com.networkcontrol.android.kotlin.ui.common

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Android 13+（API 33+）AGSL 液態玻璃著色器：
 * 對膠囊背景層施加圓角邊緣折射、輕微 RGB 色散、色彩飽和度微提與方向性菲涅爾鏡面高光。
 * 僅應套用於背景層（不可包住前景圖示與文字），確保圖示與文字維持銳利清晰；
 * Android 12 及以下系統自動安全跳過，退回精調毛玻璃效果。
 */
private const val LIQUID_GLASS_AGSL = """
uniform shader content;
uniform float2 resolution;
uniform float cornerRadius;
uniform float isDark;

float sdRoundedBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 fragCoord) {
    float2 halfSize = resolution * 0.5;
    float2 p = fragCoord - halfSize;
    float r = min(cornerRadius, min(halfSize.x, halfSize.y));
    float d = sdRoundedBox(p, halfSize, r);

    float inset = max(-d, 0.0);
    float bezelWidth = min(r * 0.65, 22.0);
    float edgeFactor = clamp(1.0 - inset / max(bezelWidth, 1.0), 0.0, 1.0);
    float lens = edgeFactor * edgeFactor;

    float2 eps = float2(1.0, 0.0);
    float nx = sdRoundedBox(p + eps.xy, halfSize, r) - sdRoundedBox(p - eps.xy, halfSize, r);
    float ny = sdRoundedBox(p + eps.yx, halfSize, r) - sdRoundedBox(p - eps.yx, halfSize, r);
    float2 n = normalize(float2(nx, ny) + float2(1e-4, 1e-4));

    float displace = lens * 14.0;
    float dispersion = lens * 1.8;
    float2 uvBase = clamp(fragCoord - n * displace, float2(0.0), resolution - float2(1.0));
    float2 uvR = clamp(uvBase - n * dispersion, float2(0.0), resolution - float2(1.0));
    float2 uvB = clamp(uvBase + n * dispersion, float2(0.0), resolution - float2(1.0));

    half rCh = content.eval(uvR).r;
    half4 gCh = content.eval(uvBase);
    half bCh = content.eval(uvB).b;
    half4 color = half4(rCh, gCh.g, bCh, gCh.a);

    half luma = dot(color.rgb, half3(0.299, 0.587, 0.114));
    color.rgb = clamp(mix(half3(luma), color.rgb, half(1.12)), half3(0.0), half3(1.0));

    float2 lightDir = normalize(float2(-0.35, -0.94));
    float specTop = pow(clamp(dot(n, lightDir), 0.0, 1.0), 2.0) * pow(edgeFactor, 2.2);
    float specBot = pow(clamp(dot(n, -lightDir), 0.0, 1.0), 2.5) * pow(edgeFactor, 3.0);
    float rimGlow = pow(edgeFactor, 3.5);

    float specStrength = mix(0.30, 0.20, isDark);
    float rimStrength = mix(0.14, 0.10, isDark);
    color.rgb = clamp(
        color.rgb + half3(specTop * specStrength + specBot * (specStrength * 0.45) + rimGlow * rimStrength),
        half3(0.0),
        half3(1.0),
    );
    return color;
}
"""

fun Modifier.liquidGlassBackdrop(
    enabled: Boolean,
    cornerRadius: Dp = 30.dp,
    dark: Boolean = false,
): Modifier = composed {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return@composed this
    }
    val density = LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }
    val shader = remember {
        runCatching { RuntimeShader(LIQUID_GLASS_AGSL) }.getOrNull()
    } ?: return@composed this

    this.graphicsLayer {
        if (size.width > 1f && size.height > 1f) {
            runCatching {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("cornerRadius", radiusPx)
                shader.setFloatUniform("isDark", if (dark) 1f else 0f)
                renderEffect = RenderEffect
                    .createRuntimeShaderEffect(shader, "content")
                    .asComposeRenderEffect()
            }
        }
    }
}
