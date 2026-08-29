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

/**
 * Каким кадром добыто это чтение (#1041, #1046).
 *
 * Тот же след работы, что и [META_READ_UPSCALE]: там сказано, во сколько раз увеличивали
 * кадр, здесь — как его готовили перед вторым заходом. Без него знание приходит с кадра,
 * которого человек никогда не видел, и понять происхождение этого текста нечем
 * (ADR-0001 §9): два чтения одного снимка расходятся, а по выжившему было не разобрать, с
 * какого кадра оно снято.
 *
 * Ключ один на все способы подготовки, а не по ключу на способ: способ называет значение.
 * Отдельный `read.straightened` рядом с `read.whitened` был бы двумя именами одной работы,
 * и каждый следующий способ заводил бы третье.
 *
 * След внутренний: он объясняет знание, а не показывается человеку просто потому, что
 * существует (§14 конституции). Находкой не считается — лежит в `PROCESS_NOTES`.
 */
const val META_READ_PREPARED = "read.prepared"

/** Кадр выровняли по свету; геометрия снимка не тронута (#1046). */
const val READ_PREPARED_WHITENED = "whitened"

/** Кадр выпрямили: страница без перспективы, слова уехали вместе с геометрией (#1041). */
const val READ_PREPARED_STRAIGHTENED = "straightened"

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
