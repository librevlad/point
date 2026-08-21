package com.point.core.flow

data class SheetPlan(
    val rows: List<List<String>>,

    val headerRows: Set<Int>,

    val candidates: Map<Pair<Int, Int>, List<String>> = emptyMap(),
)

fun sheetPlanOf(
    rows: List<List<String>>,
    candidates: Map<Pair<Int, Int>, List<String>> = emptyMap(),
): SheetPlan = SheetPlan(rows, if (rows.isEmpty()) emptySet() else setOf(0), candidates)

const val UNREAD_CAPTION = "Непрочитанное — эти слова есть на странице, но не попали ни в одну часть документа"

fun layoutSheet(layout: DocumentLayout, mode: ReadingMode = ReadingMode.UNKNOWN): SheetPlan {
    val rows = ArrayList<List<String>>()
    val headerRows = LinkedHashSet<Int>()
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()
    var captioned = false
    for (block in layout.blocks) {
        if (block.role == BlockRole.CHROME) continue
        if (block.role == BlockRole.UNREAD && !captioned) {
            captioned = true
            rows += listOf(UNREAD_CAPTION)
        }
        val grid = block.grid
        if (grid != null) {
            val from = rows.size
            grid.rows.forEach { row -> rows += row.map { marked(it, mode) } }
            repeat(minOf(block.headerRows, grid.rows.size)) { headerRows += from + it }
            grid.candidates.forEach { (cell, readings) ->
                candidates[(from + cell.first) to cell.second] = readings
            }
        } else {
            val line = buildList {
                if (block.label.isNotEmpty()) add(marked(block.label, mode))
                if (block.text.isNotEmpty() || isEmpty()) add(marked(block.text, mode))
            }

            if (line.any { it.isNotEmpty() }) rows += line
        }
    }
    return SheetPlan(rows, headerRows, candidates)
}

/**
 * Несколько страниц набора — одна таблица (#1207).
 *
 * Страницы идут одна за другой в порядке набора: строки складываются, номера строк
 * заголовков и спорных ячеек сдвигаются на длину предыдущих страниц. Шапка, которую
 * следующая страница повторяет слово в слово за первой, второй раз не кладётся — это та же
 * таблица, а не новая. Другая шапка остаётся: значит, на странице другая таблица.
 */
fun stitchSheets(pages: List<SheetPlan>): SheetPlan {
    if (pages.size == 1) return pages.single()
    val rows = ArrayList<List<String>>()
    val headerRows = LinkedHashSet<Int>()
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()
    val leadHeader = pages.firstOrNull()
        ?.let { lead -> lead.headerRows.mapNotNull { lead.rows.getOrNull(it) }.map(::rowKey) }
        .orEmpty()
        .toSet()
    pages.forEachIndexed { p, page ->
        val placed = IntArray(page.rows.size) { -1 }
        page.rows.forEachIndexed { r, row ->
            val repeatsLead = p > 0 && r in page.headerRows && rowKey(row) in leadHeader
            if (repeatsLead) return@forEachIndexed
            placed[r] = rows.size
            rows += row
            if (r in page.headerRows) headerRows += placed[r]
        }
        page.candidates.forEach { (cell, readings) ->
            val at = placed.getOrElse(cell.first) { -1 }
            if (at >= 0) candidates[at to cell.second] = readings
        }
    }
    return SheetPlan(rows, headerRows, candidates)
}

fun coveredClaim(layout: DocumentLayout, plan: SheetPlan, mode: ReadingMode): Boolean? = when {
    mode == ReadingMode.HANDWRITTEN ->
        plan.rows.asSequence().flatten().none { it.any(Char::isDigit) && !marks(it) }
    layout.coverage == null -> null
    else -> layout.uncovered.isEmpty()
}

private fun marked(text: String, mode: ReadingMode): String =
    if (text.isNotEmpty() && uncertainInExport(text, mode) && !marks(text)) "$text⚠" else text

private fun marks(text: String): Boolean = text.contains('⚠') || text.contains(STRIKE_FENCE)
