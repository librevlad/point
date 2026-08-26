package com.point.core.flow

/**
 * Что вырезать и насколько увеличить, чтобы читать показанное, а не страницу целиком (#426).
 *
 * Замер 04.08.2026: показания приборов — ноль из трёх, и провал там опаснее отказа: модель
 * уверенно отдаёт числа с шильдика, и человек получает показание, которого не было. Причина
 * не в качестве чтения, а в вопросе — страница целиком неправильный вопрос, когда нужное
 * занимает считанные проценты кадра.
 *
 * Протокол того замера в репозитории не сохранился, и документа, на который вела прежняя
 * ссылка, здесь не было ни разу (#1234): чтобы оспорить вывод, замер повторяют харнессом
 * `tools/vision/run.py`, а не ищут запись.
 *
 * Чистый расчёт: ни Bitmap, ни Android. Вырезает и увеличивает тот, у кого есть пиксели.
 */
data class FocusCropPlan(val crop: Box, val scale: Float)

fun Box.width(): Float = right - left

fun Box.heightOf(): Float = bottom - top

/** Запас вокруг показанного: буквы у самого края читаются хуже, чем в поле. */
private const val PAD_SHARE = 0.08f

private const val MIN_PAD = 12f

/** Ниже этого размера мелкое читается плохо — увеличиваем, но не бесконечно. */
internal const val MIN_READABLE_SIDE = 1400f

private const val MAX_SCALE = 4f

/** Область почти во весь кадр вырезать незачем — это та же страница. */
private const val WHOLE_PAGE_SHARE = 0.92f

fun focusCropPlan(region: Box, page: Box): FocusCropPlan? {
    val width = region.width()
    val height = region.heightOf()
    if (width <= 0f || height <= 0f) return null

    val pageWidth = page.width()
    val pageHeight = page.heightOf()
    if (pageWidth <= 0f || pageHeight <= 0f) return null
    if (width >= pageWidth * WHOLE_PAGE_SHARE && height >= pageHeight * WHOLE_PAGE_SHARE) return null

    val pad = maxOf(MIN_PAD, maxOf(width, height) * PAD_SHARE)
    val crop = Box(
        (region.left - pad).coerceIn(page.left, page.right),
        (region.top - pad).coerceIn(page.top, page.bottom),
        (region.right + pad).coerceIn(page.left, page.right),
        (region.bottom + pad).coerceIn(page.top, page.bottom),
    )
    val longSide = maxOf(crop.width(), crop.heightOf())
    val scale = if (longSide <= 0f) 1f else (MIN_READABLE_SIDE / longSide).coerceIn(1f, MAX_SCALE)
    return FocusCropPlan(crop, scale)
}
