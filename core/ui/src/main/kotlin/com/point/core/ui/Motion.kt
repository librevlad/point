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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.point.core.model.ObjectKind

/*
 * Point Motion & Sensory Language, срез M1 (docs/MOTION.md).
 * Принципы №2 (объект живой), №3 (Point думает — импульсы, не крутилка),
 * №6 (у каждого типа своя физика), №10 (аура понимания).
 */

/** Per-kind breathing physics (принцип №6): how much and how calmly the object lives. */
data class BreathSpec(val scale: Float, val periodMs: Int)

/** A photo is soft, a document is strict, an archive is heavy — barely visible, but felt. */
fun breathSpecFor(kind: ObjectKind): BreathSpec = when (kind) {
    ObjectKind.IMAGE -> BreathSpec(scale = 1.008f, periodMs = 4_200)
    ObjectKind.URL -> BreathSpec(scale = 1.006f, periodMs = 4_600)
    ObjectKind.TEXT -> BreathSpec(scale = 1.005f, periodMs = 5_000)
    ObjectKind.ZIP, ObjectKind.COLLECTION -> BreathSpec(scale = 1.004f, periodMs = 5_600)
    ObjectKind.PDF, ObjectKind.OFFICE, ObjectKind.UNKNOWN -> BreathSpec(scale = 1.003f, periodMs = 6_000)
}

/*
 * M2: bubbles are particles, not buttons (принцип №4). Each drifts weightlessly with
 * its own period and phase — the field must never move as one rigid grid.
 */

/** Per-bubble weightless drift parameters. */
data class DriftSpec(val amplitudeDp: Float, val periodMs: Int, val phaseRad: Float)

fun driftSpecFor(index: Int): DriftSpec = DriftSpec(
    amplitudeDp = 1.4f + (index % 3) * 0.4f,
    periodMs = 2_900 + (index % 5) * 340,
    phaseRad = (index * 0.9f) % (2f * Math.PI.toFloat()),
)

/** How long a tapped bubble takes to be pulled back into the object before the action
 *  dispatches — the deliberate price of the «микрорадость» rule; reduced motion skips it. */
const val BUBBLE_DEPART_MS = 180

/** The system's reduced-motion wish (animator scale 0) — all M1 dynamics freeze to static. */
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

/**
 * The living frame around the object's preview:
 * - it **breathes** with its kind's physics — the object is alive, not a thumbnail;
 * - while [thinking], a light ring pulses outward — the visible thought process
 *   (never a spinner);
 * - once [understood], the shadow warms into a soft brand-coloured **aura** —
 *   "Point понял" without a word of text.
 */
@Composable
fun AliveSurface(
    kind: ObjectKind,
    thinking: Boolean,
    understood: Boolean,
    shape: Shape,
    size: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = rememberMotionEnabled()
    val spec = remember(kind) { breathSpecFor(kind) }
    val accent = MaterialTheme.colorScheme.primary

    val breath: Float
    val pulse: Float
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
        pulse = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
            label = "pulse",
        ).value
    } else {
        breath = 1f
        pulse = 0f
    }
    val aura by animateFloatAsState(
        targetValue = if (understood) 1f else 0f,
        animationSpec = tween(900),
        label = "aura",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (motion && thinking) {
            // An impulse ring, born at the object and dissolving outward (принцип №3).
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer {
                        val grow = 1f + 0.35f * pulse
                        scaleX = grow
                        scaleY = grow
                        alpha = (1f - pulse) * 0.45f
                    }
                    .border(2.dp, accent, shape),
            )
        }
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
                ),
        ) {
            content()
        }
    }
}
