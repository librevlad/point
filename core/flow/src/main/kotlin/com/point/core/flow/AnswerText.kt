package com.point.core.flow

/**
 * Ответ модели, ставший телом объекта, — без обращения к человеку (#501).
 *
 * Владелец 04.08.2026 получил рецепт по фото продуктов, и объект-текст начинался так:
 * «**Вот вариант рецепта на основе ваших ингредиентов:**». Заголовок и суть были верные, но первая
 * строка — не содержимое документа, а реплика собеседника. Она едет дальше: в сохранённый файл, в
 * Word, в PDF, в отправку. Человек, сохранивший рецепт, получает документ, который начинается с
 * разговорной фразы.
 *
 * Это касается любого ответа, который становится объектом: «Конечно, вот…», «Ниже — …»,
 * «На основе присланного…».
 *
 * **Правило намеренно узкое.** Снимается только первая строка и только если она разом:
 * обращена к человеку (несёт вводное слово вроде «вот», «ниже», «держите», «конечно»),
 * кончается двоеточием, коротка, и под ней есть что оставить. Заголовок «Ингредиенты:» под это
 * не подходит — в нём нет ни одного вводного слова, и он остаётся на месте. Осторожность здесь
 * дороже полноты: съесть строку документа хуже, чем оставить лишнюю вежливость.
 */
fun withoutPreamble(answer: String): String {
    val text = answer.trimStart()
    val firstBreak = text.indexOf('\n')
    if (firstBreak <= 0) return answer // одна строка — она и есть ответ, снимать нечего
    val first = text.substring(0, firstBreak).trim()
    val rest = text.substring(firstBreak + 1).trimStart()
    if (rest.isBlank()) return answer
    return if (isPreamble(first)) rest else answer
}

/** Обращение к человеку, а не строка документа. */
private fun isPreamble(line: String): Boolean {
    val bare = line.removePrefix("**").removeSuffix("**").removePrefix("#").trim()
    if (!bare.endsWith(":")) return false
    if (bare.length > MAX_PREAMBLE) return false
    val lower = bare.lowercase()
    return OPENERS.any { lower.startsWith(it) || lower.contains(" $it ") }
}

/** Слова, которыми ответ представляется, а не начинается. */
private val OPENERS = listOf(
    "вот", "ниже", "держите", "конечно", "разумеется", "готово",
    "here is", "here are", "sure", "certainly",
)

/** Длиннее этого — уже не вежливость, а строка документа: рисковать ею не будем. */
private const val MAX_PREAMBLE = 120
