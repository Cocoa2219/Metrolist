/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from @kawarp/core (https://github.com/better-lyrics/kawarp):
 *
 * MIT License
 *
 * Copyright (c) 2026 Better Lyrics
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.metrolist.music.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.metrolist.music.constants.DynamicBackgroundBrightnessDefault
import com.metrolist.music.constants.DynamicBackgroundSaturationDefault
import com.metrolist.music.constants.DynamicBackgroundSpeedDefault
import com.metrolist.music.constants.DynamicBackgroundWarpDefault
import kotlin.math.floor

private const val TEXTURE_SIZE = 128
private const val BLUR_PASSES = 8
private const val TINT_INTENSITY = 0.15f
private val TINT_COLOR = floatArrayOf(0.157f, 0.157f, 0.235f)
private const val TRANSITION_MS = 1000

// Matches the Blur background's 30% black scrim so white player text stays readable.
private const val BRIGHTNESS = 0.7f

/**
 * Runs once per cover: tints dark areas, then blurs a [TEXTURE_SIZE]² copy with Kawase passes.
 * The per-frame shader only warps this small texture, so no blur or offscreen layer runs per frame.
 */
fun dynamicBackgroundTexture(cover: Bitmap): Bitmap {
    val n = TEXTURE_SIZE
    val scaled = Bitmap.createScaledBitmap(cover, n, n, true)
    val pixels = IntArray(n * n)
    scaled.getPixels(pixels, 0, n, 0, 0, n, n)
    if (scaled !== cover) scaled.recycle()

    var read = FloatArray(n * n * 3)
    for (i in pixels.indices) {
        val c = pixels[i]
        var r = (c shr 16 and 0xFF) / 255f
        var g = (c shr 8 and 0xFF) / 255f
        var b = (c and 0xFF) / 255f
        val t = ((0.299f * r + 0.587f * g + 0.114f * b) / 0.5f).coerceIn(0f, 1f)
        val k = (1f - t * t * (3f - 2f * t)) * TINT_INTENSITY
        r += (TINT_COLOR[0] - r) * k
        g += (TINT_COLOR[1] - g) * k
        b += (TINT_COLOR[2] - b) * k
        read[i * 3] = r; read[i * 3 + 1] = g; read[i * 3 + 2] = b
    }

    var write = FloatArray(read.size)
    val tap = FloatArray(3)
    repeat(BLUR_PASSES) { pass ->
        val offset = pass + 0.5f
        for (y in 0 until n) for (x in 0 until n) {
            var r = 0f; var g = 0f; var b = 0f
            for (q in 0 until 4) {
                sample(read, n, x + if (q and 1 == 0) -offset else offset, y + if (q and 2 == 0) -offset else offset, tap)
                r += tap[0]; g += tap[1]; b += tap[2]
            }
            val o = (y * n + x) * 3
            write[o] = r * 0.25f; write[o + 1] = g * 0.25f; write[o + 2] = b * 0.25f
        }
        read = write.also { write = read }
    }

    for (i in pixels.indices) {
        pixels[i] = (0xFF shl 24) or
            ((read[i * 3] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((read[i * 3 + 1] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (read[i * 3 + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
    return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
}

/** Clamp-to-edge bilinear sample with texel centers on integer coordinates. */
private fun sample(img: FloatArray, n: Int, px: Float, py: Float, out: FloatArray) {
    val fx0 = floor(px)
    val fy0 = floor(py)
    val fx = px - fx0
    val fy = py - fy0
    val x0 = fx0.toInt().coerceIn(0, n - 1)
    val x1 = (fx0.toInt() + 1).coerceIn(0, n - 1)
    val y0 = fy0.toInt().coerceIn(0, n - 1)
    val y1 = (fy0.toInt() + 1).coerceIn(0, n - 1)
    for (c in 0 until 3) {
        val top = img[(y0 * n + x0) * 3 + c] + (img[(y0 * n + x1) * 3 + c] - img[(y0 * n + x0) * 3 + c]) * fx
        val bottom = img[(y1 * n + x0) * 3 + c] + (img[(y1 * n + x1) * 3 + c] - img[(y1 * n + x0) * 3 + c]) * fx
        out[c] = top + (bottom - top) * fy
    }
}

// Kawarp's blend, domain-warp and output passes fused into one shader.
private const val DYNAMIC_BACKGROUND_AGSL = """
uniform float2 uResolution;
uniform float uTime;
uniform float uBlend;
uniform float uAlpha;
uniform float uWarp;
uniform float uSaturation;
uniform float uBrightness;
uniform shader texPrevious;
uniform shader texCurrent;

const float TEXTURE_SIZE = 128.0;
const float DITHERING = 0.008;

float3 mod289(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float2 mod289(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float3 permute(float3 x) { return mod289(((x * 34.0) + 1.0) * x); }

float snoise(float2 v) {
    const float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    float2 i = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod289(i);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m;
    m = m * m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 ox = floor(x + 0.5);
    float3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    float3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
}

float hash(float3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float t = uTime * 0.05;
    float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(uv - 0.5));
    float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));
    float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));
    float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));
    float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));
    float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;
    float2 coord = clamp(uv + warp * uWarp, 0.0, 1.0) * TEXTURE_SIZE;

    float3 color = mix(texPrevious.eval(coord).rgb, texCurrent.eval(coord).rgb, uBlend);
    float2 center = uv - 0.5;
    color *= 1.0 - dot(center, center) * 0.3;
    float gray = dot(color, float3(0.299, 0.587, 0.114));
    color = mix(float3(gray), color, uSaturation);
    color += (hash(float3(floor(fragCoord), floor(uTime * 60.0))) - 0.5) * DITHERING;
    color *= uBrightness;
    return half4(half3(clamp(color, 0.0, 1.0)) * uAlpha, uAlpha);
}
"""

/**
 * Album-art fluid background. [texture] comes from [dynamicBackgroundTexture]; a new one crossfades in.
 * Frames are only requested while [animate] is true. Below Android 13 the blurred cover is shown still.
 */
@Composable
fun DynamicBackground(
    texture: Bitmap?,
    animate: Boolean,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
    speed: Float = DynamicBackgroundSpeedDefault,
    warp: Float = DynamicBackgroundWarpDefault,
    saturation: Float = DynamicBackgroundSaturationDefault,
    brightness: Float = DynamicBackgroundBrightnessDefault,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AnimatedDynamicBackground(texture, animate, alpha, modifier, speed, warp, saturation, brightness)
    } else if (texture != null) {
        BlurredCoverBackground(texture, alpha, modifier, brightness)
    }
}

/**
 * The blurred cover from [dynamicBackgroundTexture], drawn still under a scrim. A runtime blur over the
 * whole screen needs several full-screen render targets, which pushed the GPU cache over budget with
 * Accompanist lyrics and forced every texture to re-upload each frame.
 */
@Composable
fun BlurredCoverBackground(
    texture: Bitmap,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
    brightness: Float = BRIGHTNESS,
) {
    Box(modifier) {
        Image(
            bitmap = remember(texture) { texture.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = alpha(),
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - brightness) * alpha())))
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AnimatedDynamicBackground(
    texture: Bitmap?,
    animate: Boolean,
    alpha: () -> Float,
    modifier: Modifier,
    speed: Float,
    warp: Float,
    saturation: Float,
    brightness: Float,
) {
    val latestSpeed = rememberUpdatedState(speed)
    val shader = remember { RuntimeShader(DYNAMIC_BACKGROUND_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val time = remember { mutableFloatStateOf(0f) }
    val blend = remember { Animatable(1f) }
    // The texture being faded out, and the one fading in.
    val textures = remember { mutableStateOf<Pair<BitmapShader, BitmapShader>?>(null) }

    LaunchedEffect(texture) {
        val next = texture?.let { bitmapShader(it) } ?: return@LaunchedEffect
        val previous = textures.value?.let { (from, to) -> if (blend.value >= 0.5f) to else from }
        textures.value = (previous ?: next) to next
        if (previous != null) {
            blend.snapTo(0f)
            blend.animateTo(1f, tween(TRANSITION_MS, easing = LinearEasing))
        }
    }

    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            time.floatValue += (now - last) / 1_000_000_000f * latestSpeed.value
            last = now
        }
    }

    Spacer(
        modifier.fillMaxSize().drawBehind {
            val (previous, current) = textures.value ?: return@drawBehind
            shader.setFloatUniform("uResolution", size.width, size.height)
            shader.setFloatUniform("uTime", time.floatValue)
            shader.setFloatUniform("uBlend", blend.value)
            shader.setFloatUniform("uAlpha", alpha().coerceIn(0f, 1f))
            shader.setFloatUniform("uWarp", warp)
            shader.setFloatUniform("uSaturation", saturation)
            shader.setFloatUniform("uBrightness", brightness)
            shader.setInputShader("texPrevious", previous)
            shader.setInputShader("texCurrent", current)
            drawRect(brush)
        },
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun bitmapShader(bitmap: Bitmap) =
    BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        filterMode = BitmapShader.FILTER_MODE_LINEAR
    }
