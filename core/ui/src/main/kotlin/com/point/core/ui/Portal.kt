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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
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

private val PortalPurple = PointPalette.violet
private val PortalBlue = PointPalette.cyan
private val PortalGlow = Color(0xFFB39DFF)

internal val PortalCore = Color(0xFFEAF0FF)
private val PortalBackdrop = Color(0xFF07070C)
private val PortalText = Color(0xFFEAF0FF)
private val PortalMuted = Color(0xFF9AA3B2)

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
 * Экран ожидания: портал, подпись и текущая стадия одной строкой.
 *
 * Списка шагов здесь нет и быть не может: заранее их Point не знает, а стадии приходят от
 * самого действия по одной (#810). Многошаговый чек-лист с галочками был недостижим ни при
 * каком состоянии и рисовал вид подключённого механизма (#1232).
 */
@Composable
fun BusyPortal(
    title: String,
    subtitle: String,
    stage: String?,
    modifier: Modifier = Modifier,

    onCancel: (() -> Unit)? = null,
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

            if (!stage.isNullOrBlank()) {
                Spacer(Modifier.height(28.dp))
                StageLine(stage)
            }
            if (onCancel != null) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "Отменить",
                    style = MaterialTheme.typography.labelLarge,
                    color = PortalMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun StageLine(stage: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StageMarker()
        Text(text = stage, style = MaterialTheme.typography.bodyMedium, color = PortalText)
    }
}

@Composable
private fun StageMarker() {
    val motion = rememberMotionEnabled()
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        val alpha = if (motion) {
            rememberInfiniteTransition(label = "stage").animateFloat(
                0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "stage-a",
            ).value
        } else {
            1f
        }
        Box(Modifier.size(10.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(PortalGlow))
    }
}

@Preview(name = "Портал · распознаём (network)", showBackground = true, backgroundColor = 0xFF07070C)
@Composable
private fun PreviewBusyPortalNetwork() = PointTheme(darkTheme = true) {
    BusyPortal(
        title = "Распознаём текст…",
        subtitle = "Это займёт несколько секунд",
        stage = "Читаю…",
    )
}

@Preview(name = "Портал · стадии ещё нет", showBackground = true, backgroundColor = 0xFF07070C)
@Composable
private fun PreviewBusyPortalLocal() = PointTheme(darkTheme = true) {
    BusyPortal(title = "Обрабатываю…", subtitle = "Это займёт несколько секунд", stage = null)
}
