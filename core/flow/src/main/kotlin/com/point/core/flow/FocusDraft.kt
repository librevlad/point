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
 */
data class FocusStroke(
    val points: List<FocusPoint>,
    val width: Float,
    val erase: Boolean = false,
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
     * Область, которую человек показал.
     *
     * `null` — он ещё ничего не показал. Ластик убирает мазки, которых коснулся: «убрать
     * часть выделения» без обещания попиксельной точности, которого палец всё равно не даст.
     */
    /**
     * Область, которую человек показал, — в координатах изображения.
     *
     * `null` — он ещё ничего не показал. Прилипание к содержимому здесь НЕ делается: за него
     * отвечает `snapSelection`, один на весь продукт. Здесь — только то, что нарисовал палец.
     *
     * Ластик убирает мазки, которых коснулся: «убрать часть выделения» без обещания
     * попиксельной точности, которого палец всё равно не даст.
     */
    fun region(pad: Float = 0f, page: Box? = null): Box? {
        val kept = keptStrokes()
        val rough = kept.mapNotNull { it.bounds() }.reduceOrNull { a, b -> a.union(b) } ?: return null
        val padded = Box(rough.left - pad, rough.top - pad, rough.right + pad, rough.bottom + pad)
        return if (page == null) padded else padded.clampedTo(page)
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

private fun Box.clampedTo(page: Box): Box = Box(
    left.coerceIn(page.left, page.right),
    top.coerceIn(page.top, page.bottom),
    right.coerceIn(page.left, page.right),
    bottom.coerceIn(page.top, page.bottom),
)
