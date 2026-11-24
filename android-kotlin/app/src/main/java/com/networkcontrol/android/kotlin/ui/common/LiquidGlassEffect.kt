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
 * Android 13+（API 33+）AGSL 液態玻璃著色器（iOS Liquid Glass 風格）：
 * - 所有折射與倒角參數均按螢幕密度（dp -> px）縮放，確保高 DPI 旗艦機上折射弧度依然立體明顯；
 * - 內部輕微凸透鏡放大 + 邊緣半球 Snell 曲線折射 + 棱鏡 RGB 色散 + 雙向菲涅爾鏡面高光；
 * - 僅套用於獨立背景層，前景圖示與文字保持 100% 銳利；Android 12 及以下自動安全回退。
 */
private const val LIQUID_GLASS_AGSL = """
uniform shader content;
uniform float2 resolution;
uniform float cornerRadius;
uniform float bezelWidth;
uniform float displaceMax;
uniform float dispersionMax;
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
    float bw = min(bezelWidth, min(r * 0.92, halfSize.y * 0.85));
    float t = clamp(1.0 - inset / max(bw, 1.0), 0.0, 1.0);

    // 半球曲面折射曲線：邊緣曲率急劇增強，形成明顯的液態玻璃包邊折射。
    float domeZ = sqrt(max(1.0 - t * t, 0.002));
    float rimLens = (1.0 - domeZ) * (0.35 + 0.65 * t);

    // 由 SDF 梯度計算朝外法線
    float2 eps = float2(1.25, 0.0);
    float nx = sdRoundedBox(p + eps.xy, halfSize, r) - sdRoundedBox(p - eps.xy, halfSize, r);
    float ny = sdRoundedBox(p + eps.yx, halfSize, r) - sdRoundedBox(p - eps.yx, halfSize, r);
    float2 n = normalize(float2(nx, ny) + float2(1e-4, 1e-4));

    // 中央輕微凸透鏡放大（3.5%）+ 邊緣強力向內折射位移
    float2 centerMagnify = p * 0.035 * (1.0 - t * 0.5);
    float2 rimWarp = n * (rimLens * displaceMax);
    float2 uvBase = clamp(fragCoord - centerMagnify - rimWarp, float2(0.0), resolution - float2(1.0));

    // 邊緣棱鏡 RGB 色散（僅出現在曲面倒角處，中央零色散）
    float disp = pow(t, 1.8) * dispersionMax;
    float2 uvR = clamp(uvBase - n * disp, float2(0.0), resolution - float2(1.0));
    float2 uvB = clamp(uvBase + n * disp, float2(0.0), resolution - float2(1.0));

    half rCh = content.eval(uvR).r;
    half4 gCh = content.eval(uvBase);
    half bCh = content.eval(uvB).b;
    half4 color = half4(rCh, gCh.g, bCh, gCh.a);

    // 蘋果玻璃經典色彩提純（Vibrancy）
    half luma = dot(color.rgb, half3(0.299, 0.587, 0.114));
    color.rgb = clamp(mix(half3(luma), color.rgb, half(1.22)), half3(0.0), half3(1.0));

    // 雙向光源菲涅爾高光（左上主光源 + 右下環境反射）與玻璃管壁邊緣發光
    float2 lightTop = normalize(float2(-0.30, -0.95));
    float2 lightBot = normalize(float2(0.38, 0.92));
    float specTop = pow(clamp(dot(n, lightTop), 0.0, 1.0), 1.8) * pow(t, 2.0);
    float specBot = pow(clamp(dot(n, lightBot), 0.0, 1.0), 2.2) * pow(t, 2.4);
    float fresnelRim = pow(t, 3.2);

    float specAmp = mix(0.42, 0.30, isDark);
    float rimAmp = mix(0.22, 0.16, isDark);
    half boost = half(specTop * specAmp + specBot * (specAmp * 0.55) + fresnelRim * rimAmp);
    color.rgb = clamp(color.rgb + half3(boost), half3(0.0), half3(1.0));

    // 邊緣內側極細折射暗帶，拉開玻璃厚度立體感
    float innerBand = smoothstep(0.35, 0.72, t) * (1.0 - smoothstep(0.72, 0.96, t));
    color.rgb *= half(1.0 - innerBand * mix(0.06, 0.10, isDark));

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
    val bezelWidthPx = with(density) { 18.dp.toPx() }
    val displaceMaxPx = with(density) { 24.dp.toPx() }
    val dispersionMaxPx = with(density) { 2.8.dp.toPx() }
    val shader = remember {
        runCatching { RuntimeShader(LIQUID_GLASS_AGSL) }.getOrNull()
    } ?: return@composed this

    this.graphicsLayer {
        if (size.width > 1f && size.height > 1f) {
            runCatching {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("cornerRadius", radiusPx)
                shader.setFloatUniform("bezelWidth", bezelWidthPx)
                shader.setFloatUniform("displaceMax", displaceMaxPx)
                shader.setFloatUniform("dispersionMax", dispersionMaxPx)
                shader.setFloatUniform("isDark", if (dark) 1f else 0f)
                renderEffect = RenderEffect
                    .createRuntimeShaderEffect(shader, "content")
                    .asComposeRenderEffect()
            }
        }
    }
}
