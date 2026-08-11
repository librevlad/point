package com.point.core.flow

import java.time.LocalDate

/**
 * Человеческие даты знания (#651). Голое время датой не является — это отдельно
 * держит [bareClock]; здесь — чтение настоящих дат и правило владельца:
 * «дата в прошлом не может создавать событие».
 */
private val HUMAN_DATE = Regex("""(\d{1,2})[./](\d{1,2})[./](\d{2,4})|(\d{4})-(\d{2})-(\d{2})""")

fun parseHumanDate(value: String): LocalDate? =
    HUMAN_DATE.matchEntire(value.trim())?.let(::toLocalDate)

/**
 * Календарный день значения, даже когда рядом живёт время или подпись:
 * «26.04.2026 20:04» и «01.12.2020 в 11:09» — это дни, а не нечитаемые строки.
 */
fun humanDayOf(value: String): LocalDate? =
    HUMAN_DATE.find(value)?.let(::toLocalDate)

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

private fun toLocalDate(m: MatchResult): LocalDate? = runCatching {
    if (m.groupValues[4].isNotEmpty()) {
        LocalDate.of(m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toInt())
    } else {
        val year = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
        LocalDate.of(year, m.groupValues[2].toInt(), m.groupValues[1].toInt())
    }
}.getOrNull()

/** Есть ли среди дат знания (primary и «ещё») дата сегодня или позже. */
fun hasUpcomingDate(metadata: Map<String, String>, today: LocalDate): Boolean {
    val key = META_ENTITY_PREFIX + "date"
    val values = listOfNotNull(metadata[key]) + moreOf(metadata, key)
    return values.any { humanDayOf(it)?.let { d -> !d.isBefore(today) } == true }
}
