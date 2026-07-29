package com.point.core.flow

/**
 * #200 ocr++ logic/business-rule validation — a model-free safety net over an extracted table. Types
 * each column from its CONTENT (robust to unit-name headers), then flags only high-confidence
 * violations a lone OCR would silently guess: a letter jammed against a digit in a numeric column, and
 * a broken monotone article/id run. Row 0 is the header and is never flagged. Returns the `(row, col)`
 * cells to mark for review — cheap precision, no model call.
 */
private val CORRUPT = Regex("""\d[A-Za-zА-Яа-яІЇЄіїєҐґ]|[A-Za-zА-Яа-яІЇЄіїєҐґ]\d""")
private val DATE = Regex("""^\d{1,2}\.\s?\d{1,2}(\.\s?\d{2,4})?$""")
private val NUM = Regex("""^[\d\s.,()—-]+$""")
private val ID = Regex("""\d{3,6}""")
private val CORRECTION = Regex("""~~.+?~~\s*(.*)""", RegexOption.DOT_MATCHES_ALL)
private val WORD_OK = listOf("придатно", "брак", "шт", "кг")

/** The active value: drop the ⚠ flag and the struck part of a «~~old~~ new» correction. */
private fun cellValue(raw: String): String {
    val v = raw.replace("⚠", "").trim()
    return CORRECTION.find(v)?.groupValues?.get(1)?.trim() ?: v
}

private fun classify(v: String): String? = when {
    v.isEmpty() -> null
    DATE.matches(v) -> "date"
    NUM.matches(v) -> "num"
    else -> "text"
}

fun validateTable(rows: List<List<String>>): Set<Pair<Int, Int>> {
    if (rows.size < 2) return emptySet()
    val data = rows.drop(1) // row 0 is the header
    val ncol = rows.maxOf { it.size }
    val types = (0 until ncol).map { c ->
        val seen = data.mapNotNull { classify(cellValue(it.getOrElse(c) { "" })) }
        seen.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "text"
    }
    val flags = mutableSetOf<Pair<Int, Int>>()

    // a letter jammed against a digit in a numeric column = OCR confusion (О→0, l→1, З→3, X→…)
    data.forEachIndexed { di, row ->
        for (c in 0 until ncol) {
            if (types[c] != "num") continue
            val v = cellValue(row.getOrElse(c) { "" })
            if (v.isNotEmpty() && CORRUPT.containsMatchIn(v) && WORD_OK.none { v.lowercase().contains(it) }) {
                flags.add((di + 1) to c)
            }
        }
    }

    // article/id run: a plain 3–6-digit integer column that genuinely climbs — flag any step that drops
    for (c in 0 until ncol) {
        if (types[c] != "num") continue
        val seq = data.mapIndexedNotNull { di, row ->
            val v = cellValue(row.getOrElse(c) { "" })
            if (ID.matchEntire(v) != null) (di + 1) to v.toInt() else null
        }
        val distinct = seq.map { it.second }.toSet().size
        if (seq.size >= 3 && seq.last().second > seq.first().second && distinct >= seq.size * 0.7) {
            for (i in 1 until seq.size) {
                if (seq[i].second <= seq[i - 1].second) flags.add(seq[i].first to c)
            }
        }
    }
    return flags
}
