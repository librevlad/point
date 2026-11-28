package com.point.core.flow

import com.point.core.model.tr

/**
 * Где в прочитанном тексте документа начинается страница [page] (#1014): [offset] — номер
 * символа в тексте, с которого идёт её текст. Номер страницы — по документу, с единицы.
 */
data class PageStart(val page: Int, val offset: Int)

/** Границы страниц словами знания — тем видом, каким их хранит [META_OCR_TEXT_PAGES]. */
fun pageStartsWire(starts: List<PageStart>): String = starts.joinToString(",") { "${it.page}:${it.offset}" }

/**
 * Границы страниц прочитанного текста — или пусто, если документ о них не рассказал (#1014).
 *
 * Пусто — обычный случай, а не поломка: текстовый слой PDF достаётся одним куском, а текст,
 * прочитанный до того, как границы стали знанием, их не знает. Испорченная пара пропускается,
 * а не роняет поиск: найденное важнее того, на какой оно странице.
 */
fun pageStartsOf(metadata: Map<String, String>): List<PageStart> =
    metadata[META_OCR_TEXT_PAGES].orEmpty()
        .split(',')
        .mapNotNull { pair ->
            val page = pair.substringBefore(':', "").trim().toIntOrNull()
            val offset = pair.substringAfter(':', "").trim().toIntOrNull()
            if (page == null || offset == null || page < 1 || offset < 0) null else PageStart(page, offset)
        }
        .sortedBy { it.offset }

/**
 * Где в тексте документа стоит [query] — смещения начала каждой находки (#1014).
 *
 * Правила сравнения — те же, что у поиска по странице ([foldForSearch]): регистр, пробелы,
 * тире и кавычки не различаются, а запятая между цифрами равна точке. Одно правило на оба
 * поиска нужно затем, чтобы один и тот же документ, прочитанный страницей или целиком, на
 * один и тот же запрос отвечал одинаково.
 *
 * В отличие от страницы, у текста нет строк-рамок: перенос строки — такой же пробел, и фраза,
 * которую чтение разорвало переносом, находится целиком. Находки не перекрываются.
 */
fun findInText(text: CharSequence, query: String): List<Int> {
    val needle = foldForSearch(query)
    if (needle.isEmpty()) return emptyList()
    val hay = StringBuilder(text.length)
    val owner = IntArray(text.length)
    foldForSearch(text) { ch, at ->
        owner[hay.length] = at
        hay.append(ch)
    }
    val folded = hay.toString()
    val found = mutableListOf<Int>()
    var from = 0
    while (from <= folded.length - needle.length) {
        val at = folded.indexOf(needle, from)
        if (at < 0) break
        found += owner[at]
        from = at + needle.length
    }
    return found
}

/**
 * На каких страницах стоят находки [hits] — номера по возрастанию, без повторов, или `null`,
 * если хотя бы одну находку к странице не привязать (#1014).
 *
 * Находка принадлежит той странице, на которой она начинается. Частичный ответ («на
 * страницах 2 и 5», когда третья находка неизвестно где) звучал бы как полный, поэтому
 * тогда страницы не называются вовсе.
 */
fun pagesOf(hits: List<Int>, starts: List<PageStart>): List<Int>? {
    if (starts.isEmpty()) return null
    val pages = hits.map { hit -> starts.lastOrNull { it.offset <= hit }?.page ?: return null }
    return pages.distinct().sorted()
}

/**
 * Что человек слышит после поиска по документу (#1014): сколько мест и на каких страницах.
 *
 * Страницы неизвестны — те же слова, что у поиска по странице: сказать «где» нечем, а
 * выдумывать нельзя.
 */
fun foundInDocumentLabel(hits: Int, pages: List<Int>?): String = when {
    hits == 0 || pages.isNullOrEmpty() -> foundOnPageLabel(hits)
    pages.size == 1 -> tr("Нашлось {0} {1} — на странице {2}", hits, placesWord(hits), pages.single())
    else -> tr("Нашлось {0} {1} — на страницах {2}", hits, placesWord(hits), pageList(pages))
}

/**
 * Номера страниц словами: «2 и 5», «2, 3 и 5». Длинный список не читается, поэтому после
 * [PAGES_NAMED] номеров называется только, сколько страниц ещё.
 */
private fun pageList(pages: List<Int>): String {
    if (pages.size > PAGES_NAMED + 1) {
        return tr("{0} и ещё {1}", pages.take(PAGES_NAMED).joinToString(", "), pages.size - PAGES_NAMED)
    }
    return tr("{0} и {1}", pages.dropLast(1).joinToString(", "), pages.last())
}

/** Сколько номеров страниц называется словами, прежде чем сказать «и ещё». */
private const val PAGES_NAMED = 5
