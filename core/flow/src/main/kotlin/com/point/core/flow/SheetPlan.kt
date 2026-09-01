package com.point.core.flow

data class SheetPlan(
    val rows: List<List<String>>,

    val headerRows: Set<Int>,

    val candidates: Map<Pair<Int, Int>, List<String>> = emptyMap(),

    /**
     * С какой строки идут слова, не попавшие ни в одну часть документа (#1368).
     *
     * Это знание плана, а не текст: прежде границу объявляла служебная строка
     * «Непрочитанное — …» прямо в листе, и Point рассказывал о своём чтении внутри
     * документа, который человек отдаст дальше. В лист не попадает ничего, чего не было
     * на странице; границу хвоста несёт план — для писателя и измерителей.
     *
     * `null` — хвоста нет, либо непрочитанное перемешано с документом и назвать одну
     * границу значило бы соврать.
     */
    val unreadFrom: Int? = null,

    /**
     * Какие строки пришли сеткой таблицы (#1371) — по диапазону на блок сетки.
     *
     * Это знание у плана было и терялось: `layoutSheet` плющил блоки в общий список
     * строк, и писателю было негде взять границы таблицы — потому в файле не было ни
     * рамок, ни ширин, и бланк переставал быть бланком. Хвост непрочитанного сеткой
     * не считается: его рамка объявила бы свалку таблицей.
     */
    val tables: List<IntRange> = emptyList(),

    /** Строки-заголовки документа (роль TITLE): писатель кладёт их по ширине таблицы. */
    val titles: Set<Int> = emptySet(),
)

fun sheetPlanOf(
    rows: List<List<String>>,
    candidates: Map<Pair<Int, Int>, List<String>> = emptyMap(),
): SheetPlan = SheetPlan(rows, if (rows.isEmpty()) emptySet() else setOf(0), candidates)

fun layoutSheet(layout: DocumentLayout, mode: ReadingMode = ReadingMode.UNKNOWN): SheetPlan {
    val rows = ArrayList<List<String>>()
    val headerRows = LinkedHashSet<Int>()
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()
    var unreadFrom: Int? = null
    var mixed = false
    val tables = ArrayList<IntRange>()
    val titles = LinkedHashSet<Int>()
    for (block in layout.blocks) {
        if (block.role == BlockRole.CHROME) continue
        if (block.role == BlockRole.UNREAD && unreadFrom == null) {

            // Слова страницы, не попавшие ни в одну часть документа, остаются словами
            // страницы — но отделяются пустой строкой, а не подписью Point (#1368):
            // служебных слов в файле человека не бывает.
            if (rows.isNotEmpty()) rows += listOf("")
            unreadFrom = rows.size
        }
        if (block.role != BlockRole.UNREAD && unreadFrom != null) mixed = true
        val grid = block.grid
        if (grid != null) {
            val from = rows.size
            grid.rows.forEach { row -> rows += row.map { marked(it, mode) } }
            repeat(minOf(block.headerRows, grid.rows.size)) { headerRows += from + it }
            grid.candidates.forEach { (cell, readings) ->
                candidates[(from + cell.first) to cell.second] = readings
            }

            // Сетка — таблица; хвост непрочитанного — нет (#1371).
            if (block.role != BlockRole.UNREAD && rows.size > from) tables += from until rows.size
        } else {
            val line = buildList {
                if (block.label.isNotEmpty()) add(marked(block.label, mode))
                if (block.text.isNotEmpty() || isEmpty()) add(marked(block.text, mode))
            }

            if (line.any { it.isNotEmpty() }) {
                if (block.role == BlockRole.TITLE) titles += rows.size
                rows += line
            }
        }
    }
    return SheetPlan(rows, headerRows, candidates, if (mixed) null else unreadFrom, tables, titles)
}

/**
 * Несколько страниц набора — одна таблица (#1207).
 *
 * Страницы идут одна за другой в порядке набора: строки складываются, номера строк
 * заголовков и спорных ячеек сдвигаются на длину предыдущих страниц. Шапка, которую
 * следующая страница повторяет слово в слово за первой прочитанной, второй раз не кладётся —
 * это та же таблица, а не новая. Другая шапка остаётся: значит, на странице другая таблица.
 *
 * Хвосты непрочитанного у страниц свои и остаются на своих местах; одной границы у сшитого
 * листа нет (#1368) — назвать её значило бы объявить середину документа свалкой.
 */
fun stitchSheets(pages: List<SheetPlan>): SheetPlan {
    if (pages.size == 1) return pages.single()
    val rows = ArrayList<List<String>>()
    val headerRows = LinkedHashSet<Int>()
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()
    val tables = ArrayList<IntRange>()
    val titles = LinkedHashSet<Int>()
    // Эталон шапки — первая страница, у которой шапка есть: первая страница набора могла
    // не прочитаться вовсе, и тогда её место в таблице — строка без шапки.
    val leadAt = pages.indexOfFirst { it.headerRows.isNotEmpty() }
    val leadHeader = pages.getOrNull(leadAt)
        ?.let { lead -> lead.headerRows.mapNotNull { lead.rows.getOrNull(it) }.map(::rowKey) }
        .orEmpty()
        .toSet()
    var tail: Int? = null
    pages.forEachIndexed { p, page ->
        val placed = IntArray(page.rows.size) { -1 }
        page.rows.forEachIndexed { r, row ->
            val repeatsLead = p > leadAt && r in page.headerRows && rowKey(row) in leadHeader
            if (repeatsLead) return@forEachIndexed
            placed[r] = rows.size
            rows += row
            if (r in page.headerRows) headerRows += placed[r]
        }
        page.candidates.forEach { (cell, readings) ->
            val at = placed.getOrElse(cell.first) { -1 }
            if (at >= 0) candidates[at to cell.second] = readings
        }

        // «Отсюда и до конца — непрочитанное» правдиво только у хвоста последней страницы.
        if (page === pages.last()) {
            tail = page.unreadFrom?.let { placed.getOrNull(it) }?.takeIf { it >= 0 }
        }

        // Сетки и заголовки страниц переезжают теми строками, что реально легли (#1371):
        // выкинутая шапка-повтор рвёт диапазон, и он продолжается со следующей строки.
        page.tables.forEach { range ->
            var runStart = -1
            var last = -1
            for (r in range) {
                val at = placed.getOrElse(r) { -1 }
                if (at < 0) continue
                if (runStart < 0 || at != last + 1) {
                    if (runStart >= 0) tables += runStart..last
                    runStart = at
                }
                last = at
            }
            if (runStart >= 0) tables += runStart..last
        }
        page.titles.forEach { r -> placed.getOrElse(r) { -1 }.takeIf { it >= 0 }?.let { titles += it } }
    }
    return SheetPlan(rows, headerRows, candidates, tail, tables, titles)
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
