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

fun coveredClaim(layout: DocumentLayout, plan: SheetPlan, mode: ReadingMode): Boolean? = when {
    mode == ReadingMode.HANDWRITTEN ->
        plan.rows.asSequence().flatten().none { it.any(Char::isDigit) && !marks(it) }
    layout.coverage == null -> null
    else -> layout.uncovered.isEmpty()
}

private fun marked(text: String, mode: ReadingMode): String =
    if (text.isNotEmpty() && uncertainInExport(text, mode) && !marks(text)) "$text⚠" else text

private fun marks(text: String): Boolean = text.contains('⚠') || text.contains(STRIKE_FENCE)
