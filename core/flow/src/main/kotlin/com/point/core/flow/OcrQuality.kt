package com.point.core.flow

fun looksLikeOcrGarbage(text: String): Boolean {
    val nonSpace = text.count { !it.isWhitespace() }
    if (nonSpace < 30) return false
    val readable = text.count { it.isLetterOrDigit() }
    val words = Regex("""\p{L}{4,}""").findAll(text).count()
    return readable.toDouble() / nonSpace < 0.6 || words < 3
}

fun weaklyRead(layer: AtomLayer): Boolean {
    val text = layer.text
    val confidences = layer.atoms.filter { it.text.isNotBlank() }.map { it.confidence }.sorted()

    if (confidences.isEmpty()) {

        if (layer.atoms.isNotEmpty()) return true
        return text.isBlank() || looksLikeOcrGarbage(text)
    }

    if (text.count { !it.isWhitespace() } < MIN_JUDGEABLE) return false
    val median = confidences[confidences.size / 2]
    if (median < MIN_MEDIAN_CONFIDENCE) return true

    return WORD.findAll(text).count() < MIN_WORDS
}

fun degeneratedReading(text: String, minRepeats: Int = MIN_REPEATS): String? {
    if (text.isBlank()) return "страница вернулась пустой"
    if (text.none(Char::isLetterOrDigit)) return "в ответе нет ни одной буквы и ни одной цифры"
    val repeated = longestRun(meaningfulLines(text))
    return if (repeated < minRepeats) null else "одна и та же строка повторена $repeated раз подряд"
}

private fun meaningfulLines(text: String): List<String> = text.lineSequence()
    .map(String::trim)
    .filter { it.length > MIN_MEANINGFUL_LINE && it.any(Char::isLetterOrDigit) }
    .toList()

private fun longestRun(lines: List<String>): Int {
    var best = 0
    var run = 0
    var previous: String? = null
    for (line in lines) {
        run = if (line == previous) run + 1 else 1
        previous = line
        if (run > best) best = run
    }
    return best
}

private const val MIN_REPEATS = 6

private const val MIN_MEANINGFUL_LINE = 12

private const val MIN_JUDGEABLE = 30

private const val MIN_MEDIAN_CONFIDENCE = 0.5f

private val WORD = Regex("""\p{L}{4,}""")

private const val MIN_WORDS = 3
