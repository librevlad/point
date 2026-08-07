package com.point.core.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import com.point.core.flow.Box as PageBox

internal data class PageFit(val scale: Float, val dx: Float, val dy: Float) {

    fun toScreen(box: PageBox): Rect = Rect(
        Offset(box.left * scale + dx, box.top * scale + dy),
        Offset(box.right * scale + dx, box.bottom * scale + dy),
    )

    fun toPage(x: Float, y: Float): Offset = Offset((x - dx) / scale, (y - dy) / scale)
}

internal fun pageFit(container: IntSize, imageWidth: Int, imageHeight: Int): PageFit {
    if (container == IntSize.Zero || imageWidth <= 0 || imageHeight <= 0) return PageFit(1f, 0f, 0f)
    val scale = minOf(container.width / imageWidth.toFloat(), container.height / imageHeight.toFloat())
    return PageFit(
        scale = scale,
        dx = (container.width - imageWidth * scale) / 2f,
        dy = (container.height - imageHeight * scale) / 2f,
    )
}

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
