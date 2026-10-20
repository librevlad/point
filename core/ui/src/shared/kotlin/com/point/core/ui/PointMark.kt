package com.point.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Знак Point — светящийся портал.
 *
 * Знак один, а рисовался он тремя разными наборами долей: иконка окна, мини-знак
 * компактного окна и картинка для панели задач. Копии расходились молча — у мини-знака
 * кольцо было толще и шире, хотя рядом стояла приписка «то же кольцо, что у телефона».
 *
 * Доли измерены по иконке телефона и живут теперь в одном месте вместе с генератором
 * `tools/make-icon.py`, который делает из них картинки для установщика и лаунчера.
 *
 * Рисунок лежит в общем каталоге: с макета 19.10.2026 знак стоит и в шапке окна компьютера, и
 * на главном экране телефона, и это один и тот же знак (решение владельца 20.10.2026: «логотип
 * только наш»), а не две похожие копии.
 */
internal object PointMark {

    /** Внешний край кольца и толщина линии — в долях половины кадра. */
    const val RING_OUTER = 0.583f
    const val RING_WIDTH = 0.257f

    const val HALO_OUTER = 0.681f
    const val HALO_WIDTH = 0.34f

    val Top = Color(0xFFEAF0FF)
    val Middle = Color(0xFF9B7BFF)
    val Bottom = Color(0xFF00A6FF)
    val Halo = Color(0xFF7B5CFF)
    val FieldIn = Color(0xFF141021)
    val FieldOut = Color(0xFF08080E)

    /** Свежее в очереди — метка в углу иконки. */
    val Badge = Color(0xFF00E0FF)
}

/**
 * Нарисовать знак.
 *
 * `field` — тёмное поле под кольцом: у иконки оно есть, а у мини-знака внутри окна нет,
 * там фон уже свой.
 */
internal fun DrawScope.drawPointMark(field: Boolean = true, badge: Boolean = false) {
    val c = center
    val r = size.minDimension / 2f
    val ringOuter = r * PointMark.RING_OUTER
    val ringWidth = r * PointMark.RING_WIDTH

    if (field) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to PointMark.FieldIn,
                1f to PointMark.FieldOut,
                center = c,
                radius = r,
            ),
            radius = r * 0.94f,
            center = c,
        )
        drawCircle(
            color = PointMark.Halo.copy(alpha = 0.30f),
            radius = r * (PointMark.HALO_OUTER - PointMark.HALO_WIDTH / 2f),
            center = c,
            style = Stroke(width = r * PointMark.HALO_WIDTH),
        )
    }

    // Переход растянут по самому кольцу, а не по кадру: иначе белое уходит в пустоту
    // над ним и макушка выходит сиреневой вместо белой.
    drawCircle(
        brush = Brush.verticalGradient(
            0f to PointMark.Top,
            0.45f to PointMark.Middle,
            1f to PointMark.Bottom,
            startY = c.y - ringOuter,
            endY = c.y + ringOuter,
        ),
        radius = ringOuter - ringWidth / 2f,
        center = c,
        style = Stroke(width = ringWidth),
    )

    if (badge) {
        drawCircle(
            color = PointMark.Badge,
            radius = r * 0.20f,
            center = Offset(size.width * 0.82f, size.height * 0.18f),
        )
    }
}

/**
 * Знак Point на экране — наш портал, тот же, что у иконки приложения (решение владельца
 * 20.10.2026: «логотип же только наш используй»).
 *
 * Макет 19.10.2026 рисовал на его месте свой знак — тонкое кольцо с точкой. Знак у Point один:
 * экраны берут из макета стекло, а не другой логотип. Поле под кольцом здесь не нужно — фон у
 * экрана уже свой; [field] включает его там, где знак стоит сам по себе, как иконка.
 */
@Composable
fun PointGlyph(modifier: Modifier = Modifier, size: Dp = 16.dp, field: Boolean = false) {
    Canvas(modifier.size(size)) { drawPointMark(field = field) }
}
