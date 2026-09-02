package com.point.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Кусок снимка, который виден в круге портала: квадрат со стороной [side], взятый
 * из точки ([left], [top]) исходного кадра.
 */
data class PreviewCrop(val left: Int, val top: Int, val side: Int)

/**
 * Квадрат в центре кадра размером с меньшую сторону.
 *
 * Круг заполняется целиком и без искажения пропорций: лишнее по длинной стороне
 * срезается поровну с двух краёв, центр кадра остаётся центром круга. При нечётном
 * остатке лишний пиксель уходит вправо/вниз — смещение не больше пикселя.
 */
fun centerSquareCrop(width: Int, height: Int): PreviewCrop {
    val side = minOf(width, height).coerceAtLeast(0)
    return PreviewCrop(left = (width - side) / 2, top = (height - side) / 2, side = side)
}

/** Снимок объекта в круге: центр кадра заполняет круг, углы кадра не показываются. */
@Composable
fun RoundPreview(
    image: ImageBitmap,
    size: Dp,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val crop = remember(image) { centerSquareCrop(image.width, image.height) }
    val label = contentDescription
    Canvas(
        modifier
            .size(size)
            .clip(CircleShape)
            .semantics { this.contentDescription = label },
    ) {
        if (crop.side <= 0) return@Canvas
        val side = this.size.minDimension.roundToInt()
        drawImage(
            image = image,
            srcOffset = IntOffset(crop.left, crop.top),
            srcSize = IntSize(crop.side, crop.side),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(side, side),
        )
    }
}

/** Диаметр снимка в шапке. Круг помещается внутри кольца портала. */
/**
 * Кольцо объекта. Уменьшено на четверть (#879): прежние 132 dp вместе с ореолом съедали
 * около 40% первого экрана, и первое действие оказывалось за краем — человек добирался до
 * него прокруткой. Портал должен опознавать объект, а не занимать экран.
 */
val PortalPreviewSize: Dp = 100.dp
