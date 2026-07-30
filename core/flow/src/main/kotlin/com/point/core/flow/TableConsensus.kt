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

    val nrow = ts.maxOf { it.size }
    val outRows = ArrayList<List<String>>(nrow)
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()

    for (r in 0 until nrow) {
        val ncol = ts.mapNotNull { it.getOrNull(r)?.size }.maxOrNull() ?: 0
        val row = ArrayList<String>(ncol)
        for (c in 0 until ncol) {
            // The vote itself is [agree] (#222, шаг 7) — same mechanics, no longer table-only.
            // What stays here is the table's own dressing: the ⚠ marker and the candidate cap.
            val verdict = agree(ts.mapNotNull { it.getOrNull(r)?.getOrNull(c) })
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
