package com.point.core.flow

data class SnappedSelection(

    val region: Box,

    val atoms: List<Atom>,

    val text: String,

    /**
     * Показанные места по отдельности, уже прилипшие каждое по своему правилу (#1039).
     * Пусто — место одно, и оно же [region].
     */
    val parts: List<Box> = emptyList(),
) {
    val ids: List<String> get() = atoms.map { it.id }
}

/**
 * Прилипание знает инструмент и потолок (#1037, #1039, решение владельца 21.08.2026).
 *
 * @param wholeLine мазок кистью тянет строку целиком (ТЗ Focus, 10.08.2026): человек метит
 * серединой пальца и не выцеливает начало и конец. Обводка прямоугольником или лассо — наоборот,
 * остаётся как нарисована: там человек целился сам, и слова под ней лишь входят в выделение
 * текстом, а область не растёт.
 *
 * Потолок: вдоль строки кисть тянет до её концов — для этого она и есть, — а поперёк строк
 * область не становится выше [MAX_SNAP_GROWTH] высот нарисованного. Один высокий атом (печать,
 * вертикальная линия, криво посаженная рамка) пересекает каждую строку, через которую проходит,
 * и без потолка одна обведённая строка превращалась в полосу через весь лист. Не влезло —
 * прилипание отменяется целиком и берётся нарисованное.
 */
fun AtomLayer.snapSelection(raw: Box, page: Int = 0, wholeLine: Boolean = false): SnappedSelection {

    val frame = Box(
        minOf(raw.left, raw.right),
        minOf(raw.top, raw.bottom),
        maxOf(raw.left, raw.right),
        maxOf(raw.top, raw.bottom),
    )
    val onPage = atoms.filter { it.page == page }
    val touched = onPage.filter { it.box.intersects(frame) }
    if (touched.isEmpty()) return SnappedSelection(frame, emptyList(), "")

    if (wholeLine) {
        val hit = readingOrder(touched.flatMap { lineAround(it, onPage) }.distinctBy { it.id })
        val stuck = hit.map { it.box }.reduce(Box::union)
        if (stuck.height <= frame.height * MAX_SNAP_GROWTH) return snapped(stuck, hit)
    }
    return snapped(frame, readingOrder(touched))
}

/**
 * Несколько показанных мест — одно выделение (#1039): каждое прилипает по правилу своего
 * инструмента, область — их объединение, слова — в порядке чтения и без повторов. Именно
 * эти прилипшие места видят и «Замазать», и «Взять фрагмент», и перечитывание области.
 */
fun AtomLayer.snapSelection(parts: List<FocusPart>, page: Int = 0): SnappedSelection {
    require(parts.isNotEmpty()) { "нечего прилеплять: показанных мест нет" }
    val each = parts.map { snapSelection(it.box, page, wholeLine = it.wholeLine) }
    val hit = readingOrder(each.flatMap { it.atoms }.distinctBy { it.id })
    return snapped(each.map { it.region }.reduce(Box::union), hit).copy(parts = each.map { it.region })
}

private fun snapped(region: Box, hit: List<Atom>) =
    SnappedSelection(region = region, atoms = hit, text = hit.joinToString(" ") { it.text })

/**
 * Слова той же строки, что и задетое: влево и вправо, пока разрыв не станет шире двух высот
 * строки. Широкий разрыв — уже другой столбец, а не продолжение этой мысли.
 */
private fun lineAround(atom: Atom, atoms: List<Atom>): List<Atom> {
    val line = atoms.filter { onSameLine(it.box, atom.box) }.sortedBy { it.box.left }
    if (line.isEmpty()) return listOf(atom)
    val gap = atom.box.height * MAX_GAP_IN_LINE
    val taken = mutableListOf(atom)
    var left = atom.box.left
    line.filter { it.box.right <= atom.box.left }.sortedByDescending { it.box.right }.forEach {
        if (left - it.box.right > gap) return@forEach
        taken += it
        left = it.box.left
    }
    var right = atom.box.right
    line.filter { it.box.left >= atom.box.right }.forEach {
        if (it.box.left - right > gap) return@forEach
        taken += it
        right = it.box.right
    }
    return taken
}

private fun onSameLine(one: Box, other: Box): Boolean {
    val overlap = minOf(one.bottom, other.bottom) - maxOf(one.top, other.top)
    return overlap > minOf(one.height, other.height) / 2f
}

/** Разрыв шире двух высот строки — уже другой столбец. */
private const val MAX_GAP_IN_LINE = 2f

/**
 * Во сколько раз прилипшая область может быть выше нарисованной (#1039). Мазок обычно не
 * тоньше строки, по которой ведут; три высоты оставляют место тонкому мазку по крупной строке
 * и останавливают рост на весь лист.
 */
const val MAX_SNAP_GROWTH = 3f

const val META_SELECTION_SOURCE = "selection.source"

const val META_SELECTION_IDS = "selection.ids"

const val META_SELECTION_REGION = "selection.region"

const val META_SELECTION_PAGE = "selection.page"
