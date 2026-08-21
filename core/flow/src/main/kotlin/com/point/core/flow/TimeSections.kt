package com.point.core.flow

/**
 * Время в списке объектов — структура, а не подпись в каждой строке (#880).
 *
 * Раньше каждая строка носила своё «20 минут назад», «1 час назад», «сегодня 17:27»,
 * «10 августа · 22:01». При полной прокрутке это сплошной поток без карты: человек не видит,
 * где кончилось сегодня и началось вчера, и читает одно и то же время в двух форматах
 * подряд на двух устройствах.
 *
 * Секция говорит это один раз, а строке остаётся только час. Правило общее: телефон и
 * компьютер режут список одинаково, отличается только оформление заголовка.
 */
enum class TimeSection(val label: String) {
    NOW("Сейчас"),
    TODAY("Сегодня"),
    YESTERDAY("Вчера"),
    EARLIER("Раньше"),
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/**
 * В какую секцию попадает объект.
 *
 * «Сейчас» — последний час: это то, с чем человек работает прямо сейчас, и ради чего он чаще
 * всего открывает список. Дальше режем по **календарю**, а не по прошедшим часам (#931).
 *
 * Раньше «сегодня» означало «меньше суток назад», и вчерашние объекты стояли под заголовком
 * «СЕГОДНЯ» ровно до тех пор, пока им не исполнялось двадцать четыре часа: утром в девять
 * сегодняшним числился весь вчерашний рабочий день. Заголовок секции — единственная карта в
 * длинном списке, и она врала.
 *
 * Решение владельца 13.08.2026: «Резать по календарю + свести с подписью». День здесь и день
 * в подписи строки считает один код — чтобы разойтись снова было негде.
 */
fun timeSectionOf(at: Long, now: Long, zone: java.time.ZoneId): TimeSection {
    if (now - at < HOUR) return TimeSection.NOW
    val day = dayOf(at, zone)
    val today = dayOf(now, zone)
    return when {
        day == today -> TimeSection.TODAY
        day == today.minusDays(1) -> TimeSection.YESTERDAY
        else -> TimeSection.EARLIER
    }
}

/** Календарный день метки времени. Один ответ на вопрос «какой это был день» на весь продукт. */
fun dayOf(at: Long, zone: java.time.ZoneId): java.time.LocalDate =
    java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

/**
 * Подпись времени у строки списка (#1056, решение владельца 20.08.2026, вариант B).
 *
 * Под «Сейчас / Сегодня / Вчера» день сказан секцией — строке остаётся час (#880). «Раньше» —
 * открытый хвост: секция дня не называет, и час «14:05» делал трёхдневную и трёхмесячную запись
 * неотличимыми. Поэтому под «Раньше» строка называет день — «17 авг», — а час у старых записей
 * убран как бесполезный. Чужой год — с годом: «3 мая 2025».
 *
 * Секцию и подпись считает один код на обе поверхности — телефону и компьютеру разойтись негде.
 */
fun rowTimeLabel(at: Long, now: Long, zone: java.time.ZoneId): String {
    val moment = java.time.Instant.ofEpochMilli(at).atZone(zone)
    if (timeSectionOf(at, now, zone) != TimeSection.EARLIER) {
        return "%02d:%02d".format(moment.hour, moment.minute)
    }
    val day = moment.toLocalDate()
    val date = "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}"
    return if (day.year == dayOf(now, zone).year) date else "$date ${day.year}"
}

/**
 * Список, разрезанный на секции, в порядке от свежего к старому. Пустые секции не
 * появляются: заголовок без строк — обещание, за которым ничего нет.
 */
fun <T> byTimeSection(
    items: List<T>,
    now: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    atOf: (T) -> Long,
): List<Pair<TimeSection, List<T>>> =
    TimeSection.entries.mapNotNull { section ->
        items.filter { timeSectionOf(atOf(it), now, zone) == section }
            .takeIf { it.isNotEmpty() }
            ?.let { section to it }
    }
