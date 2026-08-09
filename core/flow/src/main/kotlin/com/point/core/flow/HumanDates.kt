package com.point.core.flow

import java.time.LocalDate

/**
 * Человеческие даты знания (#651). Голое время датой не является — это отдельно
 * держит [bareClock]; здесь — чтение настоящих дат и правило владельца:
 * «дата в прошлом не может создавать событие».
 */
private val HUMAN_DATE = Regex("""(\d{1,2})[./](\d{1,2})[./](\d{2,4})|(\d{4})-(\d{2})-(\d{2})""")

fun parseHumanDate(value: String): LocalDate? {
    val m = HUMAN_DATE.matchEntire(value.trim()) ?: return null
    return runCatching {
        if (m.groupValues[4].isNotEmpty()) {
            LocalDate.of(m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toInt())
        } else {
            val year = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
            LocalDate.of(year, m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
    }.getOrNull()
}

/** Есть ли среди дат знания (primary и «ещё») дата сегодня или позже. */
fun hasUpcomingDate(metadata: Map<String, String>, today: LocalDate): Boolean {
    val key = META_ENTITY_PREFIX + "date"
    val values = listOfNotNull(metadata[key]) + moreOf(metadata, key)
    return values.any { parseHumanDate(it)?.let { d -> !d.isBefore(today) } == true }
}
