package com.point.core.flow

import kotlin.math.roundToInt

fun readingUpscale(
    widthPx: Int,
    heightPx: Int,
    textHeightPx: Int? = null,
    targetEdge: Int = READING_FRAME_PX,
    budgetPx: Long = READING_FRAME_BUDGET_PX,
): Int {
    if (widthPx <= 0 || heightPx <= 0) return 1
    var scale = if (textHeightPx != null && textHeightPx > 0) {
        stepsTo(textHeightPx.toLong(), READING_TEXT_PX.toLong())
    } else {
        stepsTo(maxOf(widthPx, heightPx).toLong(), targetEdge.toLong())
    }
    while (scale > 1 && widthPx.toLong() * heightPx * scale * scale > budgetPx) scale--
    return scale
}

private fun stepsTo(have: Long, want: Long): Int {
    var scale = 1
    while (scale < MAX_READING_FRAME_UPSCALE && have * scale < want) scale++
    return scale
}

fun typicalTextHeightPx(layer: AtomLayer): Int? {
    val transform = layer.transform
    val divisor = (transform?.upscale ?: 1).toFloat()
    val heights = layer.atoms
        .filter { it.text.isNotBlank() }
        .map { atom -> (transform?.toUpright(atom.box)?.height ?: atom.box.height) / divisor }
        .filter { it > 0f }
        .sorted()
    if (heights.size < MIN_TEXT_SAMPLE) return null
    return heights[heights.size / 2].roundToInt().coerceAtLeast(1)
}

const val READING_FRAME_PX = 2048

const val READING_TEXT_PX = 30

private const val MAX_READING_FRAME_UPSCALE = 4

private const val READING_FRAME_BUDGET_PX = 12_000_000L

private const val MIN_TEXT_SAMPLE = 10

const val META_READ_UPSCALE = "read.upscale"

fun interface FrameUpscaler<F> {

    fun scaled(frame: F, scale: Int): F
}

class ReadyFrame<F>(val frame: F, val scale: Int) {

    val upscaled: Boolean get() = scale > 1
}

fun <F> preparedForReading(
    frame: F,
    widthPx: Int,
    heightPx: Int,
    textHeightPx: Int? = null,
    upscaler: FrameUpscaler<F>,
): ReadyFrame<F> {
    val scale = readingUpscale(widthPx, heightPx, textHeightPx)
    return if (scale <= 1) ReadyFrame(frame, 1) else ReadyFrame(upscaler.scaled(frame, scale), scale)
}
