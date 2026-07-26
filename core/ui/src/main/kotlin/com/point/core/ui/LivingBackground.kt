package com.point.core.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay

/*
 * M5 (MOTION.md, принцип №5): the background is never static — a mesh of brand tints
 * drifts almost imperceptibly. «Дорого» means barely there: tiny amplitudes, minutes-long
 * period, ~8 uniform updates a second (cheap on GPU and battery). Below API 33 (no AGSL),
 * with reduced motion, or out of focus it settles into the same static gradient.
 */

private const val MESH_SHADER = """
uniform float2 size;
uniform float t;
layout(color) uniform half4 base;
layout(color) uniform half4 tintA;
layout(color) uniform half4 tintB;

half4 main(float2 p) {
    float2 uv = p / size;
    float a = 0.5 + 0.5 * sin(t * 0.11 + uv.x * 2.3 + uv.y * 1.1);
    float b = 0.5 + 0.5 * sin(t * 0.07 - uv.x * 1.4 + uv.y * 2.9 + 1.7);
    half4 c = base;
    c = mix(c, tintA, half(a * 0.045));
    c = mix(c, tintB, half(b * 0.035));
    return c;
}
"""

/** The living canvas behind every Point screen. Draws behind [content]-less modifiers —
 *  apply to the root Box of a screen. */
@Composable
fun Modifier.livingBackground(): Modifier {
    val base = MaterialTheme.colorScheme.background
    val tintA = MaterialTheme.colorScheme.primary
    val tintB = MaterialTheme.colorScheme.tertiary
    val motion = rememberMotionEnabled()

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !motion) {
        return background(
            Brush.verticalGradient(
                0f to base,
                1f to lerpToward(base, tintA, 0.03f),
            ),
        )
    }

    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        // ~8 quantised updates a second: slow enough to be free, alive enough to be felt.
        while (true) {
            delay(125)
            phase += 0.125f
        }
    }
    val shader = remember { RuntimeShader(MESH_SHADER) }
    return drawWithCache {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("t", phase)
        shader.setColorUniform("base", base.toArgb())
        shader.setColorUniform("tintA", tintA.toArgb())
        shader.setColorUniform("tintB", tintB.toArgb())
        val brush = ShaderBrush(shader)
        onDrawBehind { drawRect(brush) }
    }
}

private fun lerpToward(from: Color, to: Color, fraction: Float): Color =
    androidx.compose.ui.graphics.lerp(from, to, fraction)
