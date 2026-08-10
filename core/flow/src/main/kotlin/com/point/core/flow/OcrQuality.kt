package com.point.core.flow

/**
 * Можно ли объявить прочитанное текстом снимка.
 *
 * Сигналов два, и сказать «не разобрал» может любой из них (#694). Первый — уверенность
 * самого движка по каждому слову. Второй — состав ответа, и он единственный там, где
 * уверенности нет вовсе: чтение снаружи её не возвращает.
 *
 * От [weaklyRead] отличается вопросом. Тот отвечает «печать или рукопись» и короткую
 * подпись судить не берётся; этот отвечает «есть ли тут вообще чтение».
 */
fun poorlyRead(text: String, layer: AtomLayer? = null): Boolean =
    text.isBlank() || (layer != null && weaklyRead(layer)) || looksLikeOcrGarbage(text)

fun looksLikeOcrGarbage(text: String): Boolean {
    val nonSpace = text.count { !it.isWhitespace() }
    if (nonSpace < MIN_JUDGEABLE) return shortReadingIsGarbage(text)
    val readable = text.count { it.isLetterOrDigit() }
    return readable.toDouble() / nonSpace < MIN_READABLE_SHARE || WORD.findAll(text).count() < MIN_WORDS
}

/**
 * Короткий ответ судится составом, а не длиной: раньше всё короче тридцати символов
 * проходило за чтение, и «. aa - 11 ВЕНЕ» — дословный ответ движка на снимке без
 * текста — становилось знанием об объекте (#694).
 *
 * Настоящая короткая находка держит либо узнаваемое значение (сумма, дата, время,
 * номер, телефон, счёт), либо хотя бы одно живое слово. Мусор рассыпан на обрывки в
 * один-два символа и не держит ни того, ни другого.
 */
private fun shortReadingIsGarbage(text: String): Boolean {
    val tokens = text.split(SPACES).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return true
    if (VALUES.any { it.containsMatchIn(text) }) return false
    if (tokens.count { it.length <= FRAGMENT } * 2 > tokens.size) return true
    return !SHORT_WORD.containsMatchIn(text)
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

private const val MIN_READABLE_SHARE = 0.6

private const val FRAGMENT = 2

private val SPACES = Regex("""\s+""")

private val SHORT_WORD = Regex("""\p{L}{3,}""")

private val VALUES = listOf(
    // номер, счёт, крупная сумма
    Regex("""\d{4,}"""),
    // дата
    Regex("""\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4}"""),
    // время
    Regex("""\d{1,2}:\d{2}"""),
    // телефон
    Regex("""\+?\d[\d\s()\-]{8,}\d"""),
    // сумма с валютой
    Regex("""(?i)\d[\d\s.,]*(грн|₴|руб|₽|uah|usd|eur|€)"""),
)
