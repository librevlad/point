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
 * Пометку выдают два признака сразу, и оба обязательны.
 *
 * Форма: весь ответ — одна короткая строка, целиком взятая в скобки, с разметкой выделения
 * вокруг или без неё; иного текста рядом нет. Так сервис метит своё замечание о снимке, а
 * не текст с него: страница одной скобочной строкой не приходит.
 *
 * Слово пустоты внутри: одной формы мало — «(фото автора)» тоже одна скобочная строка, но
 * это подпись, которую человек и просил прочитать, и отдать за неё «не нашлось» значило бы
 * потерять текст. Пустоту называет общая лексика Point: отказ в начале (#656) или слово
 * отсутствия среди слов — по-русски отрицание уходит в конец или в середину («текста нет»,
 * «на изображении нет текста»). Точных строк известных отписок здесь нет: следующая
 * формулировка следующего сервиса пройдёт тем же правилом, а скобочное значение со страницы
 * без слова пустоты через него не пройдёт.
 */
fun serviceNote(text: String): Boolean {
    val line = text.trim()
    if (line.isEmpty() || line.length > MAX_NOTE || line.any { it == '\n' || it == '\r' }) return false
    val bare = line.trim { it in EMPHASIS || it.isWhitespace() }
    if (bare.length < 2) return false
    val close = NOTE_BRACKETS[bare.first()] ?: return false
    if (bare.last() != close) return false
    val inside = bare.substring(1, bare.length - 1)
    return close !in inside && notesAbsence(inside)
}

/** Замечание об отсутствии, а не значение в скобках (#1054). */
private fun notesAbsence(note: String): Boolean =
    startsWithRefusal(note) || note.lowercase().split(WORD_BREAK).any { it in ABSENCE_WORDS }

// Слово отсутствия — само по себе, не частица: «не для продажи» и «без НДС» пустоты не называют.
private val ABSENCE_WORDS = setOf(
    "нет", "немає", "отсутствует", "відсутній", "відсутня", "відсутнє",
    "none", "nothing", "empty", "blank",
)

private val WORD_BREAK = Regex("""[^\p{L}\p{N}]+""")

/** Читатель посмотрел и текста не увидел: отдал пустой лист или служебную пометку (#1054). */
fun noTextAnswer(text: String): Boolean = text.isBlank() || serviceNote(text)

private const val MAX_NOTE = 120

private const val EMPHASIS = "*_~`"

private val NOTE_BRACKETS = mapOf('[' to ']', '(' to ')', '<' to '>', '{' to '}')

private val IMAGE_LINE = Regex("""!\[[^\]]*]\([^)]*\)""")
private val IMAGE_INLINE = Regex("""!\[[^\]]*]\([^)]*\)""")
private val FENCE = Regex("""```\w*""")
private val HEADING_MARK = Regex("""^#{1,6}\s+""")
