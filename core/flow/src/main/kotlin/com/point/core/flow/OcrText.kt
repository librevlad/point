package com.point.core.flow

private val STATUS_BAR = Regex("""^\s*\d{1,2}[:.]\d{2}\b(.*)$""")

private val REAL_WORD = Regex("""[\p{L}]{4,}""")

fun stripStatusBar(text: String): String {
    val lines = text.lineSequence().toList()
    val first = lines.firstOrNull() ?: return text
    val rest = STATUS_BAR.matchEntire(first)?.groupValues?.get(1) ?: return text
    if (REAL_WORD.containsMatchIn(rest)) return text
    return lines.drop(1).joinToString("\n")
}
