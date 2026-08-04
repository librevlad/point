package com.point.core.flow

/**
 * Смысловая половина сторожа: **где прочитанному верить нельзя** (#425).
 *
 * Отличие от [degeneratedReading] — там форма, здесь смысл. Тот ловит «модель сломалась и мелет
 * одно и то же»; этот — связный, правдоподобный текст, в котором **одна деталь неверна**. На
 * прогоне 22 настоящих кадров владельца (04.08.2026, `docs/VISION-MODELS.md`) семь расхождений из
 * тех, что человек не заметил бы, были именно такими: `Бритівка, Центральна, 58б` превратилось в
 * `586` — и человек по этому маршруту поехал бы не туда.
 *
 * Уверенность модели тут не помощник: на кадре, где итог разошёлся на 8300 гривен, она была
 * высокой. Поэтому сомнение ищется не в модели, а в самом тексте — двумя дешёвыми способами,
 * каждый из которых не требует ни сети, ни второй модели.
 *
 * **Это не отказ.** Найденное сомнение — повод сказать человеку «вот здесь проверь», а не
 * выбросить результат: выбрасывать целую накладную из-за одной подозрительной ячейки хуже, чем
 * показать её с пометкой. Отказ выносит [degeneratedReading], этот сторож — подсказывает.
 */

/**
 * Где сомнение живёт в метаданных объекта.
 *
 * Рядом с [META_READING_MODE] и по тому же правилу: понимание едет вместе с объектом, а не
 * остаётся в том экране, где родилось.
 */
const val META_READING_DOUBT = "reading.doubt"

/** Одно сомнение: что именно подозрительно и где это в тексте. Читает человек. */
data class ReadingDoubt(val what: String, val where: String)

/**
 * Все сомнения по прочитанному тексту, от самого опасного к менее.
 *
 * Порядок не случаен: несошедшийся итог — единственная проверка, которая поймала уверенную ошибку
 * на замере, и стоять она обязана первой.
 */
fun readingDoubts(text: String): List<ReadingDoubt> = buildList {
    totalMismatch(text)?.let { add(it) }
    mixedScriptWords(text).take(MAX_MIXED_SHOWN).forEach {
        add(ReadingDoubt("буквы двух алфавитов в одном слове — обычная подмена при чтении", it))
    }
}

/**
 * Итог не сошёлся с суммой строк.
 *
 * Самая сильная проверка из всех, что у нас есть, и единственная, поймавшая ошибку, которую модель
 * сделала уверенно: на кадре «снято издалека» строки читались правдоподобно, а итог разошёлся на
 * 8300. Арифметика не врёт там, где врёт уверенность.
 *
 * Считается только по **таблице с числовой колонкой**: берётся последнее число каждой строки и
 * сравнивается с числом в строке итога. Нет таблицы, нет строки итога или нет чисел — сомнения
 * нет вовсе (`null`), а не «не сошлось»: молчание честнее ложной тревоги, которая приучит человека
 * не читать предупреждения.
 */
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

/**
 * Слова, в которых смешаны кириллица и латиница.
 *
 * Классическая подмена при чтении: `с` и `c`, `о` и `o`, `а` и `a`, `р` и `p` неразличимы глазом и
 * различимы машиной. Для человека такое слово выглядит правильным, а поиск, сверка и отправка по
 * нему ломаются молча.
 *
 * Слова целиком на одном алфавите не трогаем: `iPhone`, `UA`, `ПФ-115` — законные.
 */
fun mixedScriptWords(text: String): List<String> =
    WORDS.findAll(text)
        .map(MatchResult::value)
        .filter { it.length >= MIN_WORD }
        .filter { word -> word.any(::isCyrillic) && word.any(::isLatin) }
        .distinct()
        .toList()

// --- частности ----------------------------------------------------------------------------------

/** Строка, похожая на строку таблицы: есть число и есть текст. Заголовок отсеивается сам — в нём
 *  чисел нет. */
private fun looksLikeRow(line: String): Boolean =
    line.any(Char::isDigit) && line.any(Char::isLetter) && lastNumber(line) != null

/**
 * Последнее число строки — то, что в накладной и ведомости стоит в колонке суммы.
 *
 * Понимает оба разделителя копеек (`3684,20` и `3684.20`) и пробелы внутри числа (`3 684,20`,
 * в том числе неразрывный) — иначе настоящая накладная разошлась бы с самой собой на ровном месте.
 */
private fun lastNumber(line: String): Double? {
    val cleaned = line.replace(' ', ' ').replace(' ', ' ')
    val match = NUMBER.findAll(cleaned).lastOrNull() ?: return null
    val raw = match.value.replace(" ", "").replace(',', '.')
    // Число вида «1.234.567» — это не дробь, а разряды; такие не судим, чтобы не соврать.
    if (raw.count { it == '.' } > 1) return null
    return raw.toDoubleOrNull()
}

private fun money(v: Double): String = String.format("%.2f", v).replace('.', ',')

private fun isCyrillic(c: Char) = c in 'Ѐ'..'ӿ'

private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

/** Слово — только буквы: цифры и знаки внутри (`ПФ-115`, `47/2`) разрывают его намеренно, иначе
 *  любой артикул выглядел бы подозрительным. */
private val WORDS = Regex("[\\p{L}]+")

private val NUMBER = Regex("-?\\d[\\d ]*(?:[.,]\\d+)?")

/** Слова, которыми в документах называют итог — по-украински, по-русски и по-английски. */
private val TOTAL_WORDS = listOf("разом", "усього", "итого", "всего", "итог", "сума до сплати", "total")

private const val MIN_ROWS_TO_JUDGE = 3
private const val TOLERANCE = 0.01
private const val MIN_WORD = 3
private const val MAX_MIXED_SHOWN = 5
