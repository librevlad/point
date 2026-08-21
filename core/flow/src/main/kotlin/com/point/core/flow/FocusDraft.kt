package com.point.core.flow

/**
 * Черновик выделения — то, что человек рисует пальцем, пока показывает Point нужное место.
 *
 * ТЗ владельца 10.08.2026: **кисть не должна быть буквально кистью**. Человек проводит
 * примерно, а Point превращает мазок в аккуратную область, слегка прилипая к ближайшему
 * содержимому: попадать идеально не нужно. Прямоугольник и лассо — те же мазки, просто с
 * другой формой точек, поэтому отдельных сущностей под них здесь нет.
 *
 * Чистая логика: ни Android, ни Compose. Экран рисует то же самое, но решает — этот код.
 */
data class FocusPoint(val x: Float, val y: Float)

/**
 * Один мазок в координатах изображения. `width` — толщина кисти: она входит в область,
 * потому что человек метит серединой пальца, а не краем линии.
 *
 * `wholeLine` — мазок кистью (#1039): на той стороне ✓ он прилипает к задетым строкам.
 * Обводка прямоугольником или лассо остаётся как нарисована: там человек целился сам.
 */
data class FocusStroke(
    val points: List<FocusPoint>,
    val width: Float,
    val erase: Boolean = false,
    val wholeLine: Boolean = false,
) {
    /** Прямоугольник самого мазка — с учётом толщины. */
    fun bounds(): Box? {
        if (points.isEmpty()) return null
        val half = width / 2f
        var left = points.first().x
        var right = left
        var top = points.first().y
        var bottom = top
        points.forEach {
            left = minOf(left, it.x)
            right = maxOf(right, it.x)
            top = minOf(top, it.y)
            bottom = maxOf(bottom, it.y)
        }
        return Box(left - half, top - half, right + half, bottom + half)
    }
}

/**
 * Показанное место вместе с правилом прилипания для него (#1039): кисть тянет строку, обводка
 * остаётся как нарисована. Правило едет с местом, потому что инструмент — свойство мазка, а не
 * экрана: одно место могли показать кистью, соседнее — прямоугольником.
 */
data class FocusPart(val box: Box, val wholeLine: Boolean)

data class FocusDraft(
    val strokes: List<FocusStroke> = emptyList(),

    /** Прошлые состояния целиком: «очистить» отменяется одним шагом, как и мазок. */
    private val past: List<List<FocusStroke>> = emptyList(),
    private val future: List<List<FocusStroke>> = emptyList(),
) {
    val canUndo: Boolean get() = past.isNotEmpty()

    val canRedo: Boolean get() = future.isNotEmpty()

    /** Новый мазок обрывает возврат: дальше идёт другая история, а не прежняя. */
    fun add(stroke: FocusStroke): FocusDraft = FocusDraft(strokes + stroke, past + listOf(strokes), emptyList())

    fun undo(): FocusDraft =
        if (past.isEmpty()) this else FocusDraft(past.last(), past.dropLast(1), future + listOf(strokes))

    fun redo(): FocusDraft =
        if (future.isEmpty()) this else FocusDraft(future.last(), past + listOf(strokes), future.dropLast(1))

    /** «Очистить» — тоже шаг, а не обнуление: его можно отменить, как любой другой. */
    fun cleared(): FocusDraft = FocusDraft(emptyList(), past + listOf(strokes), emptyList())

    /**
     * Область, которую человек показал, — в координатах изображения.
     *
     * `null` — он ещё ничего не показал. Прилипание к содержимому здесь НЕ делается: за него
     * отвечает `snapSelection`, один на весь продукт. Здесь — только то, что нарисовал палец.
     *
     * Ластик убирает мазки, которых коснулся: «убрать часть выделения» без обещания
     * попиксельной точности, которого палец всё равно не даст.
     */
    fun region(pad: Float = 0f, page: Box? = null): Box? =
        parts(pad, page).map { it.box }.reduceOrNull(Box::union)

    /**
     * Показанные места по отдельности (#549): человек обвёл три штуки — это три места,
     * а не один прямоугольник, накрывший половину кадра вместе со всем, что между ними.
     *
     * Пересекающиеся мазки сливаются: два движения по одному месту — одно место. Если хоть
     * одно из них — кисть, место тянет строку: обещание кисти держится там, где она прошла.
     */
    fun parts(pad: Float = 0f, page: Box? = null): List<FocusPart> {
        val shown = keptStrokes().mapNotNull { stroke ->
            stroke.bounds()?.let { FocusPart(it.padded(pad, page), stroke.wholeLine) }
        }

        val merged = mutableListOf<FocusPart>()
        shown.forEach { part ->
            val touching = merged.filter { it.box.intersects(part.box) }
            merged.removeAll(touching)
            merged += touching.fold(part) { a, b -> FocusPart(a.box.union(b.box), a.wholeLine || b.wholeLine) }
        }
        return merged
    }

    private fun keptStrokes(): List<FocusStroke> {
        val kept = mutableListOf<FocusStroke>()
        strokes.forEach { stroke ->
            val rubbed = stroke.bounds()
            if (stroke.erase) {
                if (rubbed != null) kept.removeAll { it.bounds()?.intersects(rubbed) == true }
            } else {
                kept += stroke
            }
        }
        return kept
    }

}

private fun Box.padded(pad: Float, page: Box?): Box {
    val padded = Box(left - pad, top - pad, right + pad, bottom + pad)
    return if (page == null) padded else padded.clampedTo(page)
}

private fun Box.clampedTo(page: Box): Box = Box(
    left.coerceIn(page.left, page.right),
    top.coerceIn(page.top, page.bottom),
    right.coerceIn(page.left, page.right),
    bottom.coerceIn(page.top, page.bottom),
)
