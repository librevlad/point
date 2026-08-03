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

/**
 * Сторож между чужой моделью и человеком: **выродилось ли чтение** (#280).
 *
 * Возвращает причину отказа человеческими словами или `null`, если читать можно.
 *
 * Зачем он есть. На настоящей рукописи (архивный документ 1767 года) `mistral-ocr-latest` выдал
 * одну **сочинённую** строку 71 раз подряд, с обычной уверенностью и без единого слова о том, что
 * не читает (замер 04.08.2026, `docs/VISION-MODELS.md`). Снаружи это выглядит как результат: текст
 * есть, отказа нет, и человек уносит выдумку в свой документ. Это худший сорт сбоя, потому что он
 * не выглядит сбоем.
 *
 * Проверка — не про качество текста, а про **форму вырождения**, и ловится она дёшево: настоящий
 * документ не повторяет содержательную строку десятками подряд. Образец — `degenerated()` в
 * `tools/vision/score.py`, тот самый, которым замер и поймал зацикливание.
 *
 * Три сорта вырождения, и все три означают «не прочитал», а не «прочитал пусто»:
 * - **зациклился** — одна содержательная строка [minRepeats] раз подряд;
 * - **промолчал** — ответ пуст;
 * - **отдал шум** — в ответе нет ни буквы, ни цифры.
 *
 * Чего проверка НЕ делает: она не судит о правильности прочитанного. Уверенность модели врёт, но и
 * похожесть текста на документ — не улика; единственная проверка, поймавшая уверенную ошибку на
 * замере, была смысловой (сошёлся ли итог), и живёт она не здесь.
 *
 * Отдельная от [looksLikeOcrGarbage] функция, а не ветка в ней: та судит **символьную кашу**
 * телефонного движка (мало букв, нет слов), эта — **связный, но выдуманный** текст чужой модели.
 * Каша по этой мерке чиста, выдумка по той — безупречна; слить их значило бы получить одну
 * проверку, которая не ловит ни одного из двух случаев.
 */
fun degeneratedReading(text: String, minRepeats: Int = MIN_REPEATS): String? {
    if (text.isBlank()) return "страница вернулась пустой"
    if (text.none(Char::isLetterOrDigit)) return "в ответе нет ни одной буквы и ни одной цифры"
    val repeated = longestRun(meaningfulLines(text))
    return if (repeated < minRepeats) null else "одна и та же строка повторена $repeated раз подряд"
}

/**
 * Содержательные строки — те, по которым вообще можно судить о повторе.
 *
 * Короткие отбрасываются (порог из `score.py`): в таблице «7», «—» и «шт.» законно повторяются
 * десятками, и считать их зацикливанием значило бы браковать нормальные ведомости. Разделители
 * markdown-таблиц (`|---|---|`) отпадают тем же условием, что и служебный шум: в них нет ни буквы,
 * ни цифры.
 */
private fun meaningfulLines(text: String): List<String> = text.lineSequence()
    .map(String::trim)
    .filter { it.length > MIN_MEANINGFUL_LINE && it.any(Char::isLetterOrDigit) }
    .toList()

/** Самая длинная череда одинаковых строк подряд. */
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

/**
 * Столько повторов подряд документов не бывает — порог из `tools/vision/score.py`, которым замер и
 * поймал зацикливание (там модель повторилась 71 раз, то есть с запасом на порядок).
 */
private const val MIN_REPEATS = 6

/** Короче — не улика: «7», «—», «шт.» повторяются в таблицах законно. Порог тот же, что в замере. */
private const val MIN_MEANINGFUL_LINE = 12

/** Короче этого судить не по чему — тот же порог, что и у [looksLikeOcrGarbage]. */
private const val MIN_JUDGEABLE = 30

/** Ниже этого движок не читал, а угадывал: замер даёт 0,35 на каше против 0,81 на чтении. */
private const val MIN_MEDIAN_CONFIDENCE = 0.5f

/** Слово: четыре буквы подряд и больше — тот же порог, что у [looksLikeOcrGarbage]. */
private val WORD = Regex("""\p{L}{4,}""")

/** Меньше трёх слов на всей странице — читать нечего, чем бы это ни было. */
private const val MIN_WORDS = 3
