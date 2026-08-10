package com.point.core.flow

data class LayoutElement(val id: String, val text: String)

/**
 * Режет текст на пронумерованные куски для промпта модели.
 *
 * Первая граница — перенос строки: непустая строка становится одним элементом, как и
 * раньше (структурные документы вроде накладных читаются построчно — роль и поле
 * указывают на конкретную строку).
 *
 * Строка длиннее ориентира [MAX_ELEMENT_CHARS] раньше обрубалась вслепую — абзац без
 * переносов терял всё, что не поместилось в первые триста знаков, и это объявлялось
 * пониманием объекта (#682/#683). Теперь такая строка делится по границам предложений,
 * а предложение длиннее ориентира — по границам слов. Содержимое не теряется; ограничено
 * только число элементов через [limit].
 */
fun layoutOf(text: String, limit: Int = MAX_LAYOUT_ELEMENTS): List<LayoutElement> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap(::sentenceChunksOf)
        .take(limit)
        .mapIndexed { i, chunk -> LayoutElement("P${i + 1}", chunk) }
        .toList()

const val MAX_LAYOUT_ELEMENTS = 120

private const val MAX_ELEMENT_CHARS = 300

/**
 * Следующее окно чтения длинного объекта (#682/#683): не более [limit] знаков начиная с
 * [already]. Если дальше в объекте есть ещё текст, конец окна не рвёт слово пополам —
 * обрезается по ближайшей границе слова назад, а хвост уходит следующему окну. Ничего
 * не теряется: оставшийся хвост слова просто читается со следующим нажатием «Понять».
 */
fun readWindowOf(full: String, already: Int, limit: Int): String {
    val slice = full.drop(already).take(limit)
    val cutAt = already + slice.length

    // Резать нечего (конец объекта) или срез и так остановился на границе слова.
    if (cutAt >= full.length || full[cutAt].isWhitespace()) return slice

    val trimmed = slice.trimEnd()
    val cut = trimmed.indexOfLast { it.isWhitespace() }
    return if (cut > 0) trimmed.substring(0, cut) else slice
}

private val SENTENCE_BOUNDARY = Regex("""(?<=[.!?…])\s+""")

private val WHITESPACE = Regex("""\s+""")

/** Строка в пределах ориентира остаётся собой — большинство строк документа короче. */
private fun sentenceChunksOf(line: String, target: Int = MAX_ELEMENT_CHARS): List<String> {
    if (line.length <= target) return listOf(line)

    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) {
            chunks += current.toString()
            current.clear()
        }
    }

    line.split(SENTENCE_BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }.forEach { sentence ->
        when {
            // Предложение само длиннее ориентира (нет ни одной точки в потоке слов) —
            // режем по словам, а не бросаем как есть и не обрубаем посередине.
            sentence.length > target -> {
                flush()
                chunks += wordChunksOf(sentence, target)
            }
            current.isNotEmpty() && current.length + 1 + sentence.length > target -> {
                flush()
                current.append(sentence)
            }
            else -> {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
    }
    flush()
    return chunks
}

private fun wordChunksOf(text: String, target: Int): List<String> {
    val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    words.forEach { word ->
        if (current.isNotEmpty() && current.length + 1 + word.length > target) {
            chunks += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(word)
    }
    if (current.isNotEmpty()) chunks += current.toString()

    // Единственное непобиваемое пробелами слово длиннее ориентира (например, ссылка) —
    // отдаём как есть: обрубать сам токен посимвольно хуже, чем один длинный элемент.
    return chunks.ifEmpty { listOf(text) }
}
