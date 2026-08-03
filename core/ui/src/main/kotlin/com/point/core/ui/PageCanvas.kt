package com.point.core.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import com.point.core.flow.Box as PageBox

/**
 * Как страница вписана в контейнер: **одна точка правды** для рисования поверх неё и для
 * обратного пересчёта пальца в координаты страницы.
 *
 * Существует, потому что таких экранов стало два — выделение (#259) и поиск (#279), — и обе
 * стороны считают ту же геометрию. Разъехавшись на множитель, они рисовали бы подсветку рядом с
 * тем словом, про которое говорят: ложь тем более коварная, что выглядит как работающая функция.
 */
internal data class PageFit(val scale: Float, val dx: Float, val dy: Float) {

    /** Рамка страницы → прямоугольник на экране. */
    fun toScreen(box: PageBox): Rect = Rect(
        Offset(box.left * scale + dx, box.top * scale + dy),
        Offset(box.right * scale + dx, box.bottom * scale + dy),
    )

    /** Точка экрана → координаты страницы (палец рисует рамку в контейнере, не в битмапе). */
    fun toPage(x: Float, y: Float): Offset = Offset((x - dx) / scale, (y - dy) / scale)
}

/** Вписывание «целиком, по центру» — то же, что делает `ContentScale.Fit` у самой картинки. */
internal fun pageFit(container: IntSize, imageWidth: Int, imageHeight: Int): PageFit {
    if (container == IntSize.Zero || imageWidth <= 0 || imageHeight <= 0) return PageFit(1f, 0f, 0f)
    val scale = minOf(container.width / imageWidth.toFloat(), container.height / imageHeight.toFloat())
    return PageFit(
        scale = scale,
        dx = (container.width - imageWidth * scale) / 2f,
        dy = (container.height - imageHeight * scale) / 2f,
    )
}

/**
 * Подсветка мест на странице: заливка плюс обводка, по рамке на место.
 *
 * Рамки приходят **построчными** — и у захвата пальцем, и у находки поиска. Одна внешняя рамка
 * на несколько строк накрыла бы и то, что между ними не найдено, то есть утверждала бы про
 * страницу больше, чем знает (ревью #284).
 */
internal fun DrawScope.drawPageHighlights(
    fit: PageFit,
    boxes: List<PageBox>,
    color: Color,
    cornerPx: Float,
    strokePx: Float,
) {
    boxes.forEach { box ->
        val rect = fit.toScreen(box)
        drawRoundRect(
            color = color.copy(alpha = 0.24f),
            topLeft = rect.topLeft, size = rect.size,
            cornerRadius = CornerRadius(cornerPx),
        )
        drawRoundRect(
            color = color.copy(alpha = 0.85f),
            topLeft = rect.topLeft, size = rect.size,
            cornerRadius = CornerRadius(cornerPx),
            style = Stroke(width = strokePx),
        )
    }
}
