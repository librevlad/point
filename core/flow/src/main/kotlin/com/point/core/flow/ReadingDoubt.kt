package com.point.core.flow

const val META_READING_DOUBT = "reading.doubt"

data class ReadingDoubt(val what: String, val where: String)

fun readingDoubts(text: String): List<ReadingDoubt> = buildList {
    totalMismatch(text)?.let { add(it) }
    mixedScriptWords(text).take(MAX_MIXED_SHOWN).forEach {
        add(ReadingDoubt("буквы двух алфавитов в одном слове — обычная подмена при чтении", it))
    }
}

fun totalMismatch(text: String): ReadingDoubt? {
    val lines = text.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
    val totalLine = lines.lastOrNull { line -> TOTAL_WORDS.any { line.contains(it, ignoreCase = true) } }
        ?: return null
    val stated = lastNumber(totalLine) ?: return null

    val rows = lines.filter { it !== totalLine && looksLikeRow(it) }.mapNotNull(::lastNumber)
    if (rows.size < MIN_ROWS_TO_JUDGE) return null

    val sum = rows.sum()
    if (kotlin.math.abs(sum - stated) <= TOLERANCE) return null
    return ReadingDoubt(
        "итог не сошёлся: строки дают ${money(sum)}, а написано ${money(stated)}",
        totalLine,
    )
}

fun mixedScriptWords(text: String): List<String> =
    WORDS.findAll(text)
        .map(MatchResult::value)
        .filter { it.length >= MIN_WORD }
        .filter { word -> word.any(::isCyrillic) && word.any(::isLatin) }
        .distinct()
        .toList()

private fun looksLikeRow(line: String): Boolean =
    line.any(Char::isDigit) && line.any(Char::isLetter) && lastNumber(line) != null

private fun lastNumber(line: String): Double? {
    val cleaned = line.replace(' ', ' ').replace(' ', ' ')
    val match = NUMBER.findAll(cleaned).lastOrNull() ?: return null
    val raw = match.value.replace(" ", "").replace(',', '.')

    if (raw.count { it == '.' } > 1) return null
    return raw.toDoubleOrNull()
}

private fun money(v: Double): String = String.format("%.2f", v).replace('.', ',')

private fun isCyrillic(c: Char) = c in 'Ѐ'..'ӿ'

private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

private val WORDS = Regex("[\\p{L}]+")

private val NUMBER = Regex("-?\\d[\\d ]*(?:[.,]\\d+)?")

private val TOTAL_WORDS = listOf("разом", "усього", "итого", "всего", "итог", "сума до сплати", "total")

private const val MIN_ROWS_TO_JUDGE = 3
private const val TOLERANCE = 0.01
private const val MIN_WORD = 3
private const val MAX_MIXED_SHOWN = 5
