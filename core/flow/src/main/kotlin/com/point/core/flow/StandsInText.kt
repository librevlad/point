package com.point.core.flow

/**
 * Стоит ли названное моделью значение в том, что Point прочитал сам (#809).
 *
 * Решение владельца 12.08.2026: **«Нет в тексте — нет знания»**. На снимке рабочего стола
 * модель назвала дату 11/25/2025, которой на кадре нет, — и она встала рядом с настоящей
 * 11/11/2025 как равная. У Point при этом был свой текст снимка, и он себя не спросил.
 *
 * Проверка нарочно бедная и односторонняя: она умеет сказать «этого на странице нет» и
 * ничего не решает там, где страницы нет вовсе (зрячее чтение снимка без распознанных слов).
 *
 * Разделители в счёт не идут: `11/11/2025`, `11.11.2025` и `11 11 2025` — одно и то же.
 * Исправление явного искажения распознавания остаётся знанием: модель для того и смотрит на
 * кадр, чтобы починить «1ваненко ван» в «Іваненко Іван», — такое значение на странице
 * считается стоящим, [asRepair] отвечает за этот случай отдельно.
 */
fun standsInReadText(value: String, pageText: String): Boolean =
    foundLiterally(value, pageText) || asRepair(value, pageText)

/** Значение стоит на странице дословно — с точностью до разделителей и регистра. */
fun foundLiterally(value: String, pageText: String): Boolean {
    val needle = alnumFold(value)
    return needle.isNotEmpty() && alnumFold(pageText).contains(needle)
}

/**
 * Значение — починка того, что стоит на странице: слова те же, отличия в пределах явного
 * искажения распознавания ([isRepairOf]).
 */
fun asRepair(value: String, pageText: String): Boolean {
    val words = pageText.split(WHITESPACE).filter { it.isNotBlank() }
    if (words.isEmpty()) return false
    val size = value.split(WHITESPACE).count { it.isNotBlank() }.coerceIn(1, MAX_WINDOW)
    return words.windowed(size, 1, partialWindows = true)
        .any { isRepairOf(it.joinToString(" "), value) }
}

private fun alnumFold(s: String): String =
    s.lowercase().filter { it.isLetterOrDigit() }

private val WHITESPACE = Regex("""\s+""")

/** Длиннее окна значение всё равно не бывает: это значение поля, а не абзац. */
private const val MAX_WINDOW = 12
