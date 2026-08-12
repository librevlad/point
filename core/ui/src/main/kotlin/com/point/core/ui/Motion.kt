package com.point.core.ui

import android.provider.Settings
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.point.core.model.ObjectKind

data class BreathSpec(val scale: Float, val periodMs: Int)

fun breathSpecFor(kind: ObjectKind): BreathSpec = when (kind) {
    ObjectKind.IMAGE -> BreathSpec(scale = 1.008f, periodMs = 4_200)
    ObjectKind.URL -> BreathSpec(scale = 1.006f, periodMs = 4_600)
    ObjectKind.TEXT -> BreathSpec(scale = 1.005f, periodMs = 5_000)
    ObjectKind.ZIP, ObjectKind.COLLECTION -> BreathSpec(scale = 1.004f, periodMs = 5_600)

    else -> BreathSpec(scale = 1.003f, periodMs = 6_000)
}

data class ReadingSweepSpec(val vertical: Boolean, val periodMs: Int, val softness: Float)

fun readingSweepSpecFor(kind: ObjectKind): ReadingSweepSpec = when (kind) {
    ObjectKind.IMAGE -> ReadingSweepSpec(vertical = false, periodMs = 1_800, softness = 0.5f)
    ObjectKind.PDF, ObjectKind.OFFICE, ObjectKind.TEXT, ObjectKind.URL ->
        ReadingSweepSpec(vertical = true, periodMs = 1_300, softness = 0.3f)
    else -> ReadingSweepSpec(vertical = true, periodMs = 1_500, softness = 0.35f)
}

fun auraLevel(factCount: Int): Float =
    if (factCount <= 0) 0f else minOf(1f, 0.55f + 0.15f * (factCount - 1))

@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            )
        }.getOrDefault(1f) > 0f
    }
}

@Composable
fun AliveSurface(
    kind: ObjectKind,
    thinking: Boolean,
    understanding: Float,
    shape: Shape,
    size: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = rememberMotionEnabled()
    val spec = remember(kind) { breathSpecFor(kind) }
    val sweepSpec = remember(kind) { readingSweepSpecFor(kind) }
    val accent = MaterialTheme.colorScheme.primary
    val reading = motion && thinking

    val breath: Float
    val sweep: Float
    if (motion) {
        val transition = rememberInfiniteTransition(label = "alive")
        breath = transition.animateFloat(
            initialValue = 1f,
            targetValue = spec.scale,
            animationSpec = infiniteRepeatable(
                tween(spec.periodMs / 2, easing = EaseInOutSine), RepeatMode.Reverse,
            ),
            label = "breath",
        ).value
        sweep = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(sweepSpec.periodMs, easing = LinearEasing)),
            label = "sweep",
        ).value
    } else {
        breath = 1f
        sweep = 0f
    }
    val aura by animateFloatAsState(
        targetValue = understanding.coerceIn(0f, 1f),
        animationSpec = tween(900),
        label = "aura",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = breath
                    scaleY = breath
                }
                .shadow(
                    elevation = (14 + 8 * aura).dp,
                    shape = shape,
                    clip = false,
                    ambientColor = lerp(Color.Black, accent, aura),
                    spotColor = lerp(Color.Black, accent, aura),
                )
                .clip(shape)
                .drawWithContent {
                    drawContent()

                    if (reading) {
                        val band = this.size.minDimension * (0.35f + sweepSpec.softness)
                        val travel =
                            if (sweepSpec.vertical) this.size.height
                            else this.size.width + this.size.height
                        val p = sweep * (travel + band * 2f) - band
                        val start = if (sweepSpec.vertical) Offset(0f, p) else Offset(p, p)
                        val end =
                            if (sweepSpec.vertical) Offset(0f, p + band)
                            else Offset(p + band, p + band)
                        drawRect(
                            brush = Brush.linearGradient(
                                0f to Color.Transparent,
                                0.5f to accent.copy(alpha = 0.20f),
                                1f to Color.Transparent,
                                start = start,
                                end = end,
                            ),
                        )
                    }
                },
        ) {
            content()
        }
    }
}
