package com.point.core.flow

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
