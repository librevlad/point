package com.point.core.flow

import java.time.LocalDate

/**
 * Человеческие даты знания (#651). Голое время датой не является — это отдельно
 * держит [bareClock]; здесь — чтение настоящих дат и правило владельца:
 * «дата в прошлом не может создавать событие».
 */
private val HUMAN_DATE = Regex("""(\d{1,2})[./](\d{1,2})[./](\d{2,4})|(\d{4})-(\d{2})-(\d{2})""")

/**
 * Календарный день значения, даже когда рядом живёт время или подпись:
 * «26.04.2026 20:04» и «01.12.2020 в 11:09» — это дни, а не нечитаемые строки.
 *
 * Решение владельца (#802): **однозначное — читаем, спорное — остаётся текстом**.
 *
 * `11/25/2025` однозначно: двадцать пятого месяца не бывает, значит это 25 ноября. А вот
 * `03/05/2026` разрешить нечем — и день у него не появляется вовсе. Прежнее чтение всегда
 * считало первым день и молча превращало `11/25/2025` в «месяц 25», то есть ни во что.
 *
 * Месяц словом читается на русском и украинском: «5 серпня 2026» — тоже день, а раньше он
 * был датой без дня, и правило «дата в прошлом не создаёт событие» на нём не работало.
 *
 * `hint` — то, что известно про порядок частей из самого объекта (язык документа, соседние
 * даты кадра). Не известно — спорная запись остаётся текстом, и никакой «вероятный день»
 * не подставляется: выдумать день хуже, чем не прочитать.
 */
fun humanDayOf(value: String, hint: DayOrder = DayOrder.UNKNOWN): LocalDate? =
    monthByWordDay(value) ?: HUMAN_DATE.find(value)?.let { toLocalDate(it, hint) }

/** Что известно про порядок частей в записи вроде `03/05/2026`. */
enum class DayOrder { DAY_FIRST, MONTH_FIRST, UNKNOWN }

/**
 * Порядок частей по самому объекту (#802).
 *
 * Кириллица в тексте — день первым: так пишут там, где на этом языке говорят. Соседняя
 * однозначная дата того же текста весит больше языка: если рядом стоит `11/25/2025`, значит
 * автор пишет месяц первым, и `03/05` в том же тексте читается так же.
 */
fun dayOrderOf(text: String): DayOrder {
    HUMAN_DATE.findAll(text).forEach { m ->
        if (m.groupValues[4].isNotEmpty()) return@forEach
        val first = m.groupValues[1].toIntOrNull() ?: return@forEach
        val second = m.groupValues[2].toIntOrNull() ?: return@forEach
        if (first > 12 && second <= 12) return DayOrder.DAY_FIRST
        if (second > 12 && first <= 12) return DayOrder.MONTH_FIRST
    }
    return if (CYRILLIC.containsMatchIn(text)) DayOrder.DAY_FIRST else DayOrder.UNKNOWN
}

private val CYRILLIC = Regex("""\p{IsCyrillic}""")

/** Два дня — одно знание, даже когда записаны по-разному: `03.01.2026` и «3 січня 2026». */
fun sameDay(left: String, right: String, hint: DayOrder = DayOrder.UNKNOWN): Boolean {
    val a = humanDayOf(left, hint) ?: return false
    val b = humanDayOf(right, hint) ?: return false
    return a == b
}

/** «5 серпня 2026», «5 августа 2026» — день, месяц словом, год. Без года дня нет. */
private fun monthByWordDay(value: String): LocalDate? {
    val m = MONTH_BY_WORD.find(value) ?: return null
    val day = m.groupValues[1].toIntOrNull() ?: return null
    val year = m.groupValues[3].toIntOrNull() ?: return null
    val word = m.groupValues[2].lowercase()
    val month = MONTH_ORDER.indexOfFirst { word.startsWith(it) }
        .takeIf { it >= 0 }
        ?: MONTH_ORDER_UA.indexOfFirst { word.startsWith(it) }.takeIf { it >= 0 }
        ?: return null
    return runCatching { LocalDate.of(year, month + 1, day) }.getOrNull()
}

private val MONTH_BY_WORD = Regex(
    """(?iu)(?<!\d)(\d{1,2})\s+(\p{L}+)\s+(\d{4})(?!\d)""",
)

/** Основы месяцев по порядку: русский и украинский вперемешку, оба варианта на месяц. */
private val MONTH_ORDER: List<String> = listOf(
    "январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август",
    "сентябр", "октябр", "ноябр", "декабр",
)

private val MONTH_ORDER_UA: List<String> = listOf(
    "січн", "лют", "берез", "квітн", "травн", "червн",
    "липн", "серпн", "вересн", "жовтн", "листопад", "грудн",
)

/**
 * Одно правило чтения даты на все входы знания (#782, решение владельца).
 *
 * Значение даты — сама дата, а не фраза вокруг неё. «зазначених в Акті від 03.01.2026
 * № 432/69» — это 03.01.2026; «Дійсний з 05.06.2025 0:00:00 по 04.06.2027 23:59:59» —
 * два дня, а не один интервал строкой; «4.» датой не становится вовсе. Один и тот же
 * день не возвращается дважды: побеждает более информативное чтение (#660).
 *
 * Час при дате остаётся при ней — «01.12.2020 в 11:09» и «29.07 до 18:00» это срок, а
 * не отметка (#651). А относительное слово датой не становится ни с чем рядом (#784):
 * «завтра до 09:00» — указатель на день, а не день. Голое время судится своим правилом
 * и проходит нетронутым: выдумывать вместо него день нельзя.
 */
fun readDates(value: String): List<String> {
    val text = value.trim()
    if (text.isEmpty()) return emptyList()

    val found = DATE_TOKEN.findAll(text).filter { calendarShaped(it.value) }.toList()
    if (found.isEmpty()) {

        // «завтра до 09:00 это не дата» (#784, решение владельца 11.08.2026). Относительное
        // слово остаётся указателем на день, с чем бы ни стояло рядом: смысл его истёк в тот
        // момент, когда сняли кадр, и час рядом этого не чинит.
        if (holdsRelativeDayWord(text)) return emptyList()
        return if (CLOCK_INSIDE.containsMatchIn(text)) listOf(text) else emptyList()
    }

    val byDay = LinkedHashMap<String, String>()
    found.forEach { m ->
        val piece = (m.value + DATE_TAIL.matchAt(text, m.range.last + 1)?.value.orEmpty()).trim()
        val day = humanDayOf(piece)?.toString() ?: normalizedPiece(piece)
        val kept = byDay[day]
        if (kept == null || piece.length > kept.length) byDay[day] = piece
    }
    return byDay.values.toList()
}

/** Есть ли внутри значения настоящая дата — а не только цифры и точка. */
fun holdsDate(value: String): Boolean = readDates(value).isNotEmpty()

private fun normalizedPiece(piece: String) = piece.lowercase().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("""\s+""")

/**
 * Число, похожее на день и месяц: «432/69» и «4.» календарём не становятся. Порядок
 * день/месяц не навязывается — «12/25/2026» тоже дата, просто прочитанная наоборот.
 */
private fun calendarShaped(token: String): Boolean {
    if (token.any(Char::isLetter)) return true
    if (ISO_DATE.matches(token)) return humanDayOf(token) != null
    val parts = token.split('.', '/', '-').mapNotNull(String::toIntOrNull)
    if (parts.size < 2) return false
    val (a, b) = parts
    return (a in 1..31 && b in 1..12) || (a in 1..12 && b in 1..31)
}

private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

private val DATE_TOKEN = Regex(
    """(?iu)(?<!\d)\d{1,2}[./-]\d{1,2}[./-]\d{2,4}(?!\d)""" +
        """|(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)""" +
        """|(?<!\d)\d{1,2}[./]\d{1,2}(?![./\d])""" +
        """|(?<!\d)\d{1,2}\s+(?:$MONTH_STEMS)\p{L}*(?:\s+\d{4})?(?!\d)""",
)

private const val MONTH_STEMS =
    "январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|октябр|ноябр|декабр|" +
        "січн|лют|берез|квітн|травн|червн|липн|серпн|вересн|жовтн|листопад|грудн"

/** Час принадлежит своей дате: «в 11:09», «до 18:00», «0:00:00» — часть того же срока. */
private val DATE_TAIL = Regex("""(?:\s+\p{L}{1,3})?\s*(?<!\d)\d{1,2}:\d{2}(?::\d{2})?""")

private val CLOCK_INSIDE = Regex("""(?<!\d)\d{1,2}:\d{2}""")

private fun toLocalDate(m: MatchResult, hint: DayOrder): LocalDate? = runCatching {
    if (m.groupValues[4].isNotEmpty()) {
        return@runCatching LocalDate.of(
            m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toInt(),
        )
    }
    val year = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
    val first = m.groupValues[1].toInt()
    val second = m.groupValues[2].toInt()
    val dayFirst = when {

        // Двадцать пятого месяца не бывает: запись читается однозначно самой собой.
        first > 12 -> true
        second > 12 -> false

        // Обе части могли бы быть месяцем — решает то, что известно про объект. Ничего не
        // известно — дня нет: выдумать его хуже, чем не прочитать (#802).
        hint == DayOrder.DAY_FIRST -> true
        hint == DayOrder.MONTH_FIRST -> false
        else -> return@runCatching null
    }
    if (dayFirst) LocalDate.of(year, second, first) else LocalDate.of(year, first, second)
}.getOrNull()

/** Есть ли среди дат знания (primary и «ещё») дата сегодня или позже. */
fun hasUpcomingDate(metadata: Map<String, String>, today: LocalDate): Boolean =
    upcomingDateOf(metadata, today) != null

/**
 * Ближайший из будущих дней знания — тот самый, ради которого действие и предложено (#1035).
 *
 * Дверь события открывает дата сегодня или позже (#651), и она же обязана дойти до
 * результата: событие вставало на сегодня, а найденный день оставался словами внутри
 * названия. Одно правило на оба вопроса — «есть ли такой день» и «какой он».
 */
fun upcomingDateOf(metadata: Map<String, String>, today: LocalDate): LocalDate? {
    val key = META_ENTITY_PREFIX + "date"
    val values = listOfNotNull(metadata[key]) + moreOf(metadata, key)

    // Порядок частей берётся у самого объекта, а не у телефона (#802): соседние даты и язык
    // его текста. Спорная запись без такой опоры днём не становится.
    val hint = dayOrderOf((values + metadata.values).joinToString(" "))
    return values.mapNotNull { humanDayOf(it, hint) }.filter { !it.isBefore(today) }.minOrNull()
}
