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
 * Несколько календарных дат внутри одного значения — это несколько значений,
 * а не одно: «26.04.2026 26.04.2026» с чека рождало слипшийся спор. Значение
 * с одной датой (в т.ч. с временем) возвращается нетронутым.
 */
fun splitHumanDates(value: String): List<String> {
    val matches = HUMAN_DATE.findAll(value).toList()
    if (matches.size < 2) return listOf(value)
    return matches.map { it.value }.distinct()
}

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
