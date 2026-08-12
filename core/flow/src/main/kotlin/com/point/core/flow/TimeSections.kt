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
private const val DAY = 24 * HOUR

/**
 * В какую секцию попадает объект. «Сейчас» — последний час: это то, с чем человек работает
 * прямо сегодня и сейчас, и ради чего он чаще всего открывает список.
 */
fun timeSectionOf(millisAgo: Long): TimeSection = when {
    millisAgo < HOUR -> TimeSection.NOW
    millisAgo < DAY -> TimeSection.TODAY
    millisAgo < 2 * DAY -> TimeSection.YESTERDAY
    else -> TimeSection.EARLIER
}

/**
 * Список, разрезанный на секции, в порядке от свежего к старому. Пустые секции не
 * появляются: заголовок без строк — обещание, за которым ничего нет.
 */
fun <T> byTimeSection(items: List<T>, millisAgoOf: (T) -> Long): List<Pair<TimeSection, List<T>>> =
    TimeSection.entries.mapNotNull { section ->
        items.filter { timeSectionOf(millisAgoOf(it)) == section }
            .takeIf { it.isNotEmpty() }
            ?.let { section to it }
    }
