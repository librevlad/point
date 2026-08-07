package com.point.executors

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
