package com.point.core.flow

/**
 * The reconciled table from several independent model reads (#200 ocr++). [rows] is the merged table —
 * plurality value per cell, with a trailing ⚠ on any cell the models disagreed on; [candidates] holds
 * the distinct readings for each disagreed `(row, col)` so the UI can offer them for one-tap picking.
 */
data class Consensus(
    val rows: List<List<String>>,
    val candidates: Map<Pair<Int, Int>, List<String>>,
)

/**
 * Vote each cell across [tables] (independent reads of the same table). Aligns by row/column index —
 * strong vision models produce the same structure for a clean table; a shorter read simply has no
 * value to contribute for the missing cells. A cell is clean iff every present read agrees; otherwise
 * it takes the plurality raw value, is flagged ⚠, and its distinct readings become candidates.
 */
fun reconcile(tables: List<List<List<String>>>): Consensus {
    val ts = tables.filter { it.isNotEmpty() }
    if (ts.size <= 1) return Consensus(ts.firstOrNull() ?: emptyList(), emptyMap())

    // Строки выравниваются ПО СОДЕРЖИМОМУ, а не по индексу (#294): модель, пропустившая
    // строку заголовка, сдвинута целиком, и голосование по индексу сравнивало её заголовок
    // со значением соседа — ложный ⚠ на каждой ячейке и дропдауны из разнородных чтений.
    val slots = alignRows(ts)
    val outRows = ArrayList<List<String>>(slots.size)
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()

    slots.forEachIndexed { r, slot ->
        val ncol = slot.mapNotNull { it?.size }.maxOrNull() ?: 0
        val row = ArrayList<String>(ncol)
        for (c in 0 until ncol) {
            // The vote itself is [agree] (#222, шаг 7) — same mechanics, no longer table-only.
            // What stays here is the table's own dressing: the ⚠ marker and the candidate cap.
            val verdict = agree(slot.mapNotNull { it?.getOrNull(c) })
            if (verdict == null) {
                row.add(""); continue
            }
            if (verdict.agreed) {
                row.add(verdict.value) // every present read agrees
            } else {
                row.add(if (verdict.value.contains('⚠')) verdict.value else "${verdict.value}⚠")
                candidates[r to c] = verdict.candidates.filter { it.length <= 80 }
            }
        }
        outRows.add(row)
    }
    return Consensus(outRows, candidates)
}

/**
 * Строки всех чтений, разложенные по слотам общей сетки (#294).
 *
 * Слот — одна строка документа: `slot[i]` — как её прочитала таблица `i`, либо `null`, если
 * это чтение строки не увидело. Пропущенная строка честно голосуется как **отсутствие**
 * ([agree] «ничего не прочитано ≠ спор»), а не как чужая строка.
 *
 * Выравнивание — наибольшая общая подпоследовательность по «похожести строк»
 * ([rowsSimilar]): порядок строк документа сохраняется всеми чтениями, поэтому перестановки
 * не ищутся — только пропуски и вставки. Первое чтение задаёт сетку, каждое следующее
 * пристраивается к ней, а его собственные находки становятся новыми слотами на своём месте.
 */
internal fun alignRows(tables: List<List<List<String>>>): List<List<List<String>?>> {
    var slots: MutableList<MutableList<List<String>?>> =
        tables.first().mapTo(mutableListOf()) { mutableListOf<List<String>?>(it) }
    for (t in 1 until tables.size) {
        val rows = tables[t]
        val grid = slots.map { slot -> slot.firstOrNull { it != null }!! }
        val next = mutableListOf<MutableList<List<String>?>>()
        for ((slotIdx, rowIdx) in matchRows(grid, rows)) {
            when {
                slotIdx != null && rowIdx != null -> next += slots[slotIdx].also { it += rows[rowIdx] }
                slotIdx != null -> next += slots[slotIdx].also { it += null }
                // Строка, которой в сетке ещё не было: у прежних чтений её просто нет.
                rowIdx != null -> next += MutableList<List<String>?>(t) { null }.also { it += rows[rowIdx] }
            }
        }
        slots = next
    }
    return slots
}

/**
 * Пары «слот сетки ↔ строка чтения» в порядке документа: `null` с одной стороны — пропуск.
 * Классический LCS: длина совпадений максимизируется, порядок сохраняется.
 */
private fun matchRows(
    grid: List<List<String>>,
    rows: List<List<String>>,
): List<Pair<Int?, Int?>> {
    val n = grid.size
    val m = rows.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (rowsSimilar(grid[i], rows[j])) {
                lcs[i + 1][j + 1] + 1
            } else {
                maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
    }
    val out = mutableListOf<Pair<Int?, Int?>>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            rowsSimilar(grid[i], rows[j]) -> { out += i to j; i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> { out += i to null; i++ }
            else -> { out += null to j; j++ }
        }
    }
    while (i < n) out += i++ to null
    while (j < m) out += null to j++
    return out
}

/**
 * Одна ли это строка документа: большинство сопоставимых ячеек читаются одинаково после
 * свёртки формата ([normConsensus]). Сравниваются только позиции, где обе стороны непусты —
 * иначе короткое чтение строки не совпало бы с полным ни с одной.
 */
private fun rowsSimilar(a: List<String>, b: List<String>): Boolean {
    var comparable = 0
    var same = 0
    for (c in 0 until minOf(a.size, b.size)) {
        val x = normConsensus(a[c])
        val y = normConsensus(b[c])
        if (x.isEmpty() || y.isEmpty()) continue
        comparable++
        if (x == y) same++
    }
    return comparable > 0 && same * 2 >= comparable
}
