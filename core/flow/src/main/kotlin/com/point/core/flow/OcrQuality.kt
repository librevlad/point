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
    if (readable.toDouble() / nonSpace < MIN_READABLE_SHARE) return true
    return WORD.findAll(text).count() < MIN_WORDS && !holdsValue(text)
}

/**
 * Держит ли прочитанное узнаваемое значение: сумму, дату, время, номер, счёт, телефон.
 *
 * Признак не зависит от длины ответа (#1391). Выделенная человеком строка накладной держит дату
 * и сумму ровно так же, как держала бы их целая страница, — а «не меньше трёх слов от четырёх
 * букв» написано под страницу. По этой мерке кусок, выбранный человеком, выбрасывался: движок
 * прочитал его дословно и уверенно, разметка слов легла на устройство, а текста у объекта не
 * появлялось, и рядом предлагалось прочитать заново.
 *
 * Мусор от этого прочтением не становится: дословный ответ движка на снимке без текста
 * («. aa - 11 ВЕНЕ», #694) не держит ни одного такого значения.
 */
private fun holdsValue(text: String): Boolean = VALUES.any { it.containsMatchIn(text) }

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
    if (holdsValue(text)) return false
    if (tokens.count { it.length <= FRAGMENT } * 2 > tokens.size) return true
    return !SHORT_WORD.containsMatchIn(text)
}

/**
 * Лучшее из двух чтений одного и того же кадра (#1041).
 *
 * Второе чтение — второй заход по выпрямленной копии, и «не каша» ещё не значит «лучше».
 * Поиск границ листа промахивается обычным своим промахом: рамкой становится не лист, а чек
 * или карточка внутри кадра. Тогда со второго захода приходят чистые десять слов из угла — а
 * первым заходом длинный счёт прочитался почти целиком, просто неуверенно. Взять последнее
 * чтение значило бы молча потерять прочитанное.
 *
 * Мера у обоих одна: сколько живого человек из чтения получит. Живое — слово в три буквы или
 * число в две цифры; обрывок в один-два знака (`©`, `&`, `E`) находкой не был ни на одном
 * кадре. Считаются только слова, которым движок верит: догадка ниже [CONFIDENT_WORD] дойдёт
 * до человека мусором. Уверенности нет вовсе (чтение снаружи её не возвращает) — судится
 * сам текст.
 *
 * Поровну — остаётся первое: оно с того самого кадра, которым поделился человек, и его
 * координаты слов стоят там, куда он смотрит (#1013).
 */
fun betterReading(first: AtomLayer, second: AtomLayer): AtomLayer =
    if (liveWords(second) > liveWords(first)) second else first

private fun liveWords(layer: AtomLayer): Int {
    if (layer.atoms.isEmpty()) return LIVE.findAll(layer.text).count()
    return layer.atoms
        .filter { it.confidence >= CONFIDENT_WORD }
        .sumOf { LIVE.findAll(it.text).count() }
}

private val LIVE = Regex("""\p{L}{3,}|\d{2,}""")

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

    return WORD.findAll(text).count() < MIN_WORDS && !holdsValue(text)
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

/**
 * Черновик ли это чтения: верить о написанном странице или глазам (#770).
 *
 * Слово с низкой уверенностью — догадка движка, а не чтение. Когда таких почти половина,
 * разбор по странице строится на догадках: на почтовой наклейке владельца он менял местами
 * отправителя с получателем, выдавал телефон за номер накладной и сочинял «г. Лумброван».
 *
 * Замер 11.08.2026 на дословных выводах устройства: наклейка — 0.43 неуверенных слов,
 * чистое чтение таблицы — 0.25. Порог стоит между ними и назван, а не подобран на глаз:
 * почти каждое второе слово — догадка.
 *
 * Уверенности нет вовсе (чтение снаружи её не возвращает) — судить нечем, и страница
 * остаётся главной: это не черновик.
 */
fun draftReading(layer: AtomLayer?): Boolean {
    val confidences = layer?.atoms.orEmpty().filter { it.text.isNotBlank() }.map { it.confidence }
    if (confidences.size < MIN_JUDGEABLE_WORDS) return false
    return confidences.count { it < CONFIDENT_WORD } >= confidences.size * DRAFT_SHARE
}

/** Ниже этого слово прочитано неуверенно — тот же порог, по которому страница делится на столбцы. */
private const val CONFIDENT_WORD = 0.6f

private const val DRAFT_SHARE = 0.4f

private const val MIN_JUDGEABLE_WORDS = 8
