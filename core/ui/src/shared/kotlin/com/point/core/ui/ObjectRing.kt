package com.point.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Кольцо вещи — граница, через которую она пришла (макет 19.10.2026): градиент фиолетовый →
 * синий → голубой, внутри светлый диск под знак вида, вокруг мягкое свечение.
 *
 * Рисунок общий: на телефоне и в окне компьютера у вещи одно и то же кольцо, разного размера.
 * Доли сняты с макета — у телефона кольцо 53 из 112, у окна 40 из 84, это одна доля.
 *
 * Кольцо стоит неподвижно: прежние встречные кольца портала двигались всегда и говорили «идёт
 * работа», которой не было.
 */
fun DrawScope.drawObjectRing() {
    val w = size.minDimension
    val middle = Offset(size.width / 2, size.height / 2)
    drawCircle(
        Brush.radialGradient(listOf(PointPalette.violet.copy(alpha = 0.22f), Color.Transparent), middle, w * RING_GLOW),
        radius = w * RING_GLOW,
        center = middle,
    )
    drawCircle(
        Brush.linearGradient(
            0f to PointPalette.violet,
            0.55f to PointPalette.blue,
            1f to PointPalette.cyan,
            start = Offset(middle.x - w / 2, middle.y - w / 2),
            end = Offset(middle.x + w / 2, middle.y + w / 2),
        ),
        radius = w * RING_RADIUS,
        center = middle,
        style = Stroke(width = 2.dp.toPx()),
    )
    val disk = w * RING_DISK
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)),
            center = Offset(middle.x, middle.y - disk + disk * 2 * 0.28f),
            radius = disk * 2 * 0.7f,
        ),
        radius = disk,
        center = middle,
    )
    drawCircle(Color.White.copy(alpha = 0.18f), radius = disk, center = middle, style = Stroke(width = 1.dp.toPx()))
}

/** Радиус линии кольца в долях стороны. */
private const val RING_RADIUS = 40f / 84f

/** Светлый диск внутри кольца — под знак вида или снимок. */
const val RING_DISK = 33f / 84f

/** Свечение вокруг кольца. */
private const val RING_GLOW = 0.78f
