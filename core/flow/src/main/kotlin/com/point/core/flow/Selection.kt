package com.point.core.flow

data class SnappedSelection(

    val region: Box,

    val atoms: List<Atom>,

    val text: String,

    val lineRegions: List<Box> = emptyList(),
) {
    val ids: List<String> get() = atoms.map { it.id }
}

/**
 * @param wholeLine мазок кистью тянет строку целиком (ТЗ Focus, 10.08.2026): человек метит
 * серединой пальца и не выцеливает начало и конец. Обведённый прямоугольник — наоборот,
 * берёт ровно то, что обвели, дополняя лишь до целых слов: там человек целился сам.
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

    val grown = if (wholeLine) touched.flatMap { lineAround(it, onPage) }.distinctBy { it.id } else touched
    val hit = readingOrder(grown)
    return SnappedSelection(
        region = hit.map { it.box }.reduce(Box::union),
        atoms = hit,
        text = hit.joinToString(" ") { it.text },
        lineRegions = lines(hit).map { line -> line.map { it.box }.reduce(Box::union) },
    )
}

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

const val META_SELECTION_SOURCE = "selection.source"

const val META_SELECTION_IDS = "selection.ids"

const val META_SELECTION_REGION = "selection.region"

const val META_SELECTION_PAGE = "selection.page"
