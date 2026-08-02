package com.point.core.flow

/**
 * Tesseract on a photographed document often returns *gibberish* (symbols and isolated 1-2 char
 * fragments) rather than empty — so a blank-check alone never catches it. This flags that gibberish
 * by two cheap signals: too little readable content among the non-space characters, or almost no
 * real (4+ letter) words. Shared by the OCR realizer chain (fall back to cloud) and the OCR enricher
 * (discard silently). A false positive just means less automation — so this errs toward flagging.
 *
 * **Цифра — такое же прочитанное, как буква** (замер примеров 02.08.2026). Раньше здесь считались
 * только буквы, и снимок экрана учётной программы — таблица договоров, прочитанная движком
 * начисто, — получал долю букв 0,39 и объявлялся мусором: в нём номера, даты и суммы, то есть
 * ровно то, ради чего человек его и открыл. Документ, состоящий из чисел, — не «плохо прочитанный
 * документ», а обычный документ Point.
 */
fun looksLikeOcrGarbage(text: String): Boolean {
    val nonSpace = text.count { !it.isWhitespace() }
    if (nonSpace < 30) return false // too short to judge — let it through
    val readable = text.count { it.isLetterOrDigit() }
    val words = Regex("""\p{L}{4,}""").findAll(text).count()
    return readable.toDouble() / nonSpace < 0.6 || words < 3
}

/**
 * Прочитал ли движок страницу на самом деле — по тому, что он говорит **о себе**, а не по составу
 * символов.
 *
 * Доля букв как признак не работает, и это показал замер на живых примерах владельца:
 *
 * | | уверенность движка | доля букв |
 * |---|---|---|
 * | снимок экрана с таблицей договоров, прочитан начисто | **0,81** | 0,39 |
 * | фотография ведомости, на выходе символьная каша | **0,35** | 0,62 |
 *
 * По доле букв эти два случая стоят наоборот: чистое чтение бракуется, каша проходит. Уверенность
 * же разделяет их вдвое, и это не эвристика поверх текста, а собственное показание движка —
 * единственный источник, который знает, угадывал он или читал.
 *
 * Медиана, а не среднее: на странице всегда есть несколько мусорных огрызков по краям, и среднее
 * они утягивают, а медиана — нет. Пустые атомы в счёт не идут: у «ничего не прочитано» уверенности
 * не бывает.
 */
fun weaklyRead(layer: AtomLayer): Boolean {
    val text = layer.text
    val confidences = layer.atoms.filter { it.text.isNotBlank() }.map { it.confidence }.sorted()
    // Читатель без геометрии — законная конфигурация (#280: облачный слой, текстовый движок):
    // он отдаёт текст и не отдаёт слов с уверенностью. Судить его нечем, кроме самого текста, —
    // и это прежний признак, а не отказ читать.
    if (confidences.isEmpty()) {
        // Атомы есть, но все пустые — движок обошёл страницу и не собрал ни слова.
        if (layer.atoms.isNotEmpty()) return true
        return text.isBlank() || looksLikeOcrGarbage(text)
    }
    // Слишком мало прочитанного, чтобы судить, — и это не приговор: короткая подпись под фото
    // или номер на бирке страницей не притворяются, а браковать их не за что. Оговорка та же,
    // что была у [looksLikeOcrGarbage], и убрать её значило бы объявить рукописью каждый ярлык.
    if (text.count { !it.isWhitespace() } < MIN_JUDGEABLE) return false
    val median = confidences[confidences.size / 2]
    if (median < MIN_MEDIAN_CONFIDENCE) return true
    // Уверенно прочитанные огрызки — всё ещё огрызки: страница без единого слова не документ.
    return WORD.findAll(text).count() < MIN_WORDS
}

/** Короче этого судить не по чему — тот же порог, что и у [looksLikeOcrGarbage]. */
private const val MIN_JUDGEABLE = 30

/** Ниже этого движок не читал, а угадывал: замер даёт 0,35 на каше против 0,81 на чтении. */
private const val MIN_MEDIAN_CONFIDENCE = 0.5f

/** Слово: четыре буквы подряд и больше — тот же порог, что у [looksLikeOcrGarbage]. */
private val WORD = Regex("""\p{L}{4,}""")

/** Меньше трёх слов на всей странице — читать нечего, чем бы это ни было. */
private const val MIN_WORDS = 3
