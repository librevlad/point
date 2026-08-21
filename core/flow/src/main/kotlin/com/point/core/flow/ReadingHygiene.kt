package com.point.core.flow

/**
 * Гигиена прочитанного: то, что пришло снаружи (модель, облачный читатель), редко
 * бывает чистым значением. Здесь — общие правила «это вообще не значение», найденные
 * живыми прогонами 2026-08-09.
 */

/**
 * Отказ, принятый за данные (#656): модель отвечает не словом «нет», а фразой —
 * «нет номера», «не знайдено координат», «no phone found». Такая строка рождала
 * чипы вида «Отследить отправление [нет но…» — дверь, отрицающую саму себя.
 */
fun startsWithRefusal(text: String): Boolean {
    val t = text.trim().trimStart('[', '(', '«', '"').lowercase()
    return REFUSAL_STARTS.any { t == it || t.startsWith("$it ") || t.startsWith("$it,") }
}

private val REFUSAL_STARTS = listOf(
    "нет", "не найдено", "не найден", "не найдена", "не указан", "не указано", "отсутствует",
    "немає", "не знайдено", "не вказано", "відсутнє", "відсутній",
    "no", "none", "not found", "not specified", "unknown", "n/a",
)

/**
 * Markdown-обёртка облачного читателя (#661): «![img-0.jpeg](img-0.jpeg)», «# Заголовок»,
 * ограждения ``` — язык разметки провайдера, а не страницы. Уходил в текст объекта и
 * дальше в знание. Смысловые строки не трогаются.
 */
fun stripMarkdownChrome(text: String): String = text.lineSequence()
    .filterNot { IMAGE_LINE.matches(it.trim()) || FENCE.matches(it.trim()) }
    .map { line -> line.replace(HEADING_MARK, "").replace(IMAGE_INLINE, "") }
    .joinToString("\n")
    .trim()

/**
 * Служебная пометка читателя вместо текста страницы (#1054): OCR.space на снимке без единой
 * надписи отвечал «*[No text detected]*», и эта отписка ложилась текстом объекта — Point
 * предлагал «Понять» и «Перевести» фразу, которую сам же получил вместо текста. Гейт
 * бессмыслицы [degeneratedReading] её не ловит: она состоит из нормальных слов.
 *
 * Узнаётся не по словам, а по форме: весь ответ — одна короткая строка, целиком взятая в
 * скобки, с разметкой выделения вокруг или без неё. Так сервис помечает своё замечание о
 * снимке, а не текст с него: страница одной скобочной строкой не приходит. Словарь известных
 * отписок здесь не нужен — следующая формулировка у следующего сервиса пройдёт той же формой.
 */
fun serviceNote(text: String): Boolean {
    val line = text.trim()
    if (line.isEmpty() || line.length > MAX_NOTE || line.any { it == '\n' || it == '\r' }) return false
    val bare = line.trim { it in EMPHASIS || it.isWhitespace() }
    if (bare.length < 2) return false
    val close = NOTE_BRACKETS[bare.first()] ?: return false
    if (bare.last() != close) return false
    val inside = bare.substring(1, bare.length - 1)
    return close !in inside && inside.any(Char::isLetter)
}

/** Читатель посмотрел и текста не увидел: отдал пустой лист или служебную пометку (#1054). */
fun noTextAnswer(text: String): Boolean = text.isBlank() || serviceNote(text)

private const val MAX_NOTE = 120

private const val EMPHASIS = "*_~`"

private val NOTE_BRACKETS = mapOf('[' to ']', '(' to ')', '<' to '>', '{' to '}')

private val IMAGE_LINE = Regex("""!\[[^\]]*]\([^)]*\)""")
private val IMAGE_INLINE = Regex("""!\[[^\]]*]\([^)]*\)""")
private val FENCE = Regex("""```\w*""")
private val HEADING_MARK = Regex("""^#{1,6}\s+""")

/**
 * Арифметика — не сумма (#662): «127*4.32=548,64» и «500+548,64=1048,64» с чека
 * приходили кандидатами суммы. Значение суммы — одно число, а не выкладка.
 */
fun looksLikeExpression(value: String): Boolean =
    EXPRESSION_SIGNS.containsMatchIn(value) || NUMBER_RUN.findAll(value).count() > 1

private val EXPRESSION_SIGNS = Regex("""[*/=×]|\d\s*[+\-]\s*\d""")

// Пробел внутри числа — разряды («1 200,50» — одна сумма), а не второе число.
private val NUMBER_RUN = Regex("""\d{1,3}(?:[  ]\d{3})+(?:[.,]\d+)?|\d+(?:[.,]\d+)?""")
