package com.point.core.ui

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.point.core.ui.theme.PointTheme

/*
 * The neon "portal" (redesign slice 1 — plan cheerful-seeking-deer): a glowing vortex, the brand
 * mark Point wears while it reads/thinks (MOTION.md принцип №3 — impulses, not a spinner). Palette
 * is the icon's own violet-blue neon, on its own near-black stage — independent of the app's orange
 * accent and of the system light/dark theme. Pure Compose, zero dependencies.
 */

private val PortalPurple = Color(0xFF7C4DFF)
private val PortalBlue = Color(0xFF3B82F6)
private val PortalGlow = Color(0xFFB39DFF)
private val PortalCore = Color(0xFFEAF0FF)
private val PortalBackdrop = Color(0xFF07070C)
private val PortalText = Color(0xFFEAF0FF)
private val PortalMuted = Color(0xFF9AA3B2)
private val PortalDim = Color(0xFF565C6E)

/** Which indicative step the checklist highlights after [elapsedSeconds] — never past the last. */
fun portalStep(elapsedSeconds: Int, stepCount: Int, secondsPerStep: Int = 4): Int {
    if (stepCount <= 1) return 0
    return (elapsedSeconds / secondsPerStep).coerceIn(0, stepCount - 1)
}

/**
 * The glowing vortex: two counter-rotating sweep-gradient rings over a soft radial glow, breathing
 * with a gentle pulse. [intensity] scales the glow (hook for future customisation). Reduced motion
 * freezes it to a single still glowing ring.
 */
@Composable
fun Portal(modifier: Modifier = Modifier, size: Dp = 200.dp, intensity: Float = 1f) {
    val motion = rememberMotionEnabled()
    val transition = rememberInfiniteTransition(label = "portal")
    val spin = if (motion) {
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin").value
    } else {
        0f
    }
    val spinBack = if (motion) {
        transition.animateFloat(360f, 0f, infiniteRepeatable(tween(6500, easing = LinearEasing)), label = "spinBack").value
    } else {
        0f
    }
    val pulse = if (motion) {
        transition.animateFloat(
            0.97f, 1.03f, infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse), label = "pulse",
        ).value
    } else {
        1f
    }

    Canvas(modifier.size(size)) {
        val c = center
        val rad = this.size.minDimension / 2f
        val a = intensity.coerceIn(0f, 1.4f)

        // Soft radial glow behind the rings.
        drawCircle(
            brush = Brush.radialGradient(
                0f to PortalPurple.copy(alpha = 0.28f * a),
                0.55f to PortalBlue.copy(alpha = 0.13f * a),
                1f to Color.Transparent,
                center = c, radius = rad,
            ),
            radius = rad,
        )

        val outerR = rad * 0.80f * pulse
        val innerR = rad * 0.54f * pulse
        // Two passes per ring — wide+faint, then narrow+bright — fake a bloom without a blur pass.
        val bloom = listOf(0.14f to 4.0f, 0.9f to 1.0f)

        rotate(spin, c) {
            for ((alpha, widthMul) in bloom) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, PortalBlue, PortalGlow, PortalCore, PortalGlow, PortalBlue, Color.Transparent),
                        center = c,
                    ),
                    radius = outerR,
                    style = Stroke(width = rad * 0.055f * widthMul),
                    alpha = (alpha * a).coerceIn(0f, 1f),
                )
            }
        }
        rotate(spinBack, c) {
            for ((alpha, widthMul) in bloom) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, PortalPurple, PortalCore, PortalPurple, Color.Transparent),
                        center = c,
                    ),
                    radius = innerR,
                    style = Stroke(width = rad * 0.042f * widthMul),
                    alpha = (alpha * a).coerceIn(0f, 1f),
                )
            }
        }
    }
}

/**
 * The full "reading" stage shown while an action runs: the portal on its own near-black backdrop,
 * the action [title], a [subtitle], and an indicative [steps] checklist highlighting [activeStep].
 */
@Composable
fun BusyPortal(
    title: String,
    subtitle: String,
    steps: List<String>,
    activeStep: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize().background(PortalBackdrop),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp),
        ) {
            Portal(size = 208.dp)
            Spacer(Modifier.height(30.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = PortalText,
                textAlign = TextAlign.Center,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = PortalMuted)
            }
            if (steps.size > 1) {
                Spacer(Modifier.height(28.dp))
                StepChecklist(steps, activeStep)
            }
        }
    }
}

@Composable
private fun StepChecklist(steps: List<String>, active: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { i, step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepMarker(done = i < active, current = i == active)
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        i == active -> PortalText
                        i < active -> PortalMuted
                        else -> PortalDim
                    },
                )
            }
        }
    }
}

@Composable
private fun StepMarker(done: Boolean, current: Boolean) {
    val motion = rememberMotionEnabled()
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        when {
            done -> Text(
                "✓",
                color = PortalGlow,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
            )
            current -> {
                val alpha = if (motion) {
                    rememberInfiniteTransition(label = "step").animateFloat(
                        0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "step-a",
                    ).value
                } else {
                    1f
                }
                Box(Modifier.size(10.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(PortalGlow))
            }
            else -> Box(Modifier.size(7.dp).clip(CircleShape).background(PortalDim))
        }
    }
}

@Preview(name = "Портал · распознаём (network)", showBackground = true, backgroundColor = 0xFF07070C)
@Composable
private fun PreviewBusyPortalNetwork() = PointTheme(darkTheme = true) {
    BusyPortal(
        title = "Распознаём текст…",
        subtitle = "Это займёт несколько секунд",
        steps = listOf("Отправляю в облако…", "Модель обрабатывает запрос…", "Собираю ответ…"),
        activeStep = 1,
    )
}

@Preview(name = "Портал · локальное (один шаг)", showBackground = true, backgroundColor = 0xFF07070C)
@Composable
private fun PreviewBusyPortalLocal() = PointTheme(darkTheme = true) {
    BusyPortal(title = "Обрабатываю…", subtitle = "Это займёт несколько секунд", steps = listOf("Обрабатываю…"), activeStep = 0)
}
