package com.point.core.flow

/**
 * Сколько файлов из набора не открылось при приёме (#1304).
 *
 * Обычная metadata-конвенция, а не новый тип: потеря — свойство приёма, и знанием об объекте
 * она не становится. Ключ есть только там, где потеря была: «ничего не потеряно» молчит.
 */
const val META_INGEST_ASKED = "ingest.asked"

/** Сколько файлов набора открылось. Ставится вместе с [META_INGEST_ASKED]. */
const val META_INGEST_OPENED = "ingest.opened"

/**
 * След потери — только когда потеря была.
 *
 * Человек передал два фото, вышла одна страница — и узнавал он об этом страницей, которой
 * нет. Правда говорится сразу при приёме и там, где случилась: несостоявшийся файл не
 * отменяет остальных, но и не исчезает молча.
 */
fun ingestLoss(asked: Int, opened: Int): Map<String, String> =
    if (asked <= opened) {
        emptyMap()
    } else {
        mapOf(
            META_INGEST_ASKED to asked.toString(),
            META_INGEST_OPENED to opened.toString(),
        )
    }

/**
 * Что сказать человеку о потере при приёме — или `null`, если терять было нечего.
 *
 * Слово о потере — не отказ: набор принят и работает. Сказано ровно то, что произошло, без
 * догадок о причине: почему чужое приложение не отдало файл, Point не знает.
 */
fun ingestLossNote(metadata: Map<String, String>): String? {
    val asked = metadata[META_INGEST_ASKED]?.toIntOrNull() ?: return null
    val opened = metadata[META_INGEST_OPENED]?.toIntOrNull() ?: return null
    if (asked <= opened) return null
    return "Из $asked ${filesAfterCount(asked)} открыл${opened.opened()} $opened"
}

/** «из 2 файлов», но «из 21 файла». */
private fun filesAfterCount(n: Int): String =
    if (n % 10 == 1 && n % 100 != 11) "файла" else "файлов"

/** «открылся 1», но «открылось 2». */
private fun Int.opened(): String =
    if (this % 10 == 1 && this % 100 != 11) "ся" else "ось"
