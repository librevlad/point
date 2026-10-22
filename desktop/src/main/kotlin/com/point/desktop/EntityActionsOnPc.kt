package com.point.desktop

import com.point.core.flow.CalendarInserter
import com.point.core.flow.UrlOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Чем компьютер открывает письмо и карту (#1379): «Написать письмо» и «Открыть на карте» —
 * те же действия, что на телефоне, а органы свои.
 *
 * Письмо уходит почтовой программе компьютера. У карты на компьютере нет приложения, которое
 * понимало бы `geo:`, — её открывает браузер на поиске адреса, ровно как телефонные Карты.
 */
class PcUrlOpener(
    private val mail: (URI) -> Unit = { java.awt.Desktop.getDesktop().mail(it) },
    private val browse: (URI) -> Unit = { java.awt.Desktop.getDesktop().browse(it) },
) : UrlOpener {

    override suspend fun open(url: String) = withContext(Dispatchers.IO) {
        when {
            url.startsWith("mailto:") -> mail(URI(url))
            url.startsWith("geo:") -> browse(URI(mapSearchLink(url)))
            else -> browse(URI(url))
        }
    }
}

/** `geo:0,0?q=адрес` — в поиск карты в браузере. */
internal fun mapSearchLink(geo: String): String {
    val query = geo.substringAfter("?q=", "").let { URLDecoder.decode(it, Charsets.UTF_8) }
    return "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(query, Charsets.UTF_8)
}

/**
 * «Создать событие» на компьютере (#1379): событие становится файлом календаря, и его
 * открывает календарь, который у человека стоит, — Outlook, «Календарь» Windows или другой.
 * Как и на телефоне, само событие создаёт человек там: Point только подаёт его заполненным.
 */
class PcCalendar(
    private val folder: () -> File = { File(System.getProperty("java.io.tmpdir"), "point-events").apply { mkdirs() } },
    private val open: (File) -> Unit = { java.awt.Desktop.getDesktop().open(it) },
    private val today: () -> LocalDate = { LocalDate.now() },
) : CalendarInserter {

    override suspend fun insertEvent(title: String, day: LocalDate?) = withContext(Dispatchers.IO) {
        val file = File(folder(), "event-${System.currentTimeMillis()}.ics")
        file.writeText(eventFile(title, day ?: today()), Charsets.UTF_8)
        open(file)
    }
}

/** Событие на весь день — время Point не знает, а день знает (#1035). */
internal fun eventFile(title: String, day: LocalDate): String {
    val date = DateTimeFormatter.BASIC_ISO_DATE
    val stamp = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
    return listOf(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//Point//Point//EN",
        "BEGIN:VEVENT",
        "UID:${java.util.UUID.randomUUID()}@point",
        "DTSTAMP:$stamp",
        "DTSTART;VALUE=DATE:${day.format(date)}",
        "DTEND;VALUE=DATE:${day.plusDays(1).format(date)}",
        "SUMMARY:${icsText(title)}",
        "END:VEVENT",
        "END:VCALENDAR",
    ).joinToString("\r\n", postfix = "\r\n")
}

/** Запятая, точка с запятой и перевод строки в тексте календаря экранируются (RFC 5545). */
private fun icsText(text: String): String = text
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\r", "")
    .replace("\n", "\\n")
