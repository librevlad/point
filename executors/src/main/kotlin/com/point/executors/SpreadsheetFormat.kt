package com.point.executors

/**
 * Lays spreadsheet rows out as fixed-width text so a monospace PDF render reads as a table:
 * every column is padded to its widest cell (capped at [maxColWidth]), cells joined by a
 * two-space gutter. Over-wide cells are ellipsised so one runaway value can't blow the page width.
 */
internal fun formatSpreadsheet(rows: List<List<String>>, maxColWidth: Int = 40): String {
    if (rows.isEmpty()) return ""
    val columns = rows.maxOf { it.size }
    val widths = IntArray(columns) { c ->
        rows.maxOf { row -> (row.getOrNull(c) ?: "").length }.coerceIn(1, maxColWidth)
    }
    return rows.joinToString("\n") { row ->
        (0 until columns).joinToString("  ") { c ->
            val cell = row.getOrNull(c).orEmpty()
            val fitted = if (cell.length > widths[c]) cell.take(widths[c] - 1) + "…" else cell
            fitted.padEnd(widths[c])
        }.trimEnd()
    }
}
