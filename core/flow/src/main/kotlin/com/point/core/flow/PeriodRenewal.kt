package com.point.core.flow

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class DocumentPeriod(val from: LocalDate, val to: LocalDate) {

    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1

    fun next(): DocumentPeriod = DocumentPeriod(from.plusDays(days), to.plusDays(days))
}

data class PeriodReading(
    val period: DocumentPeriod,
    val dateColumn: Int,
    val dayRows: List<Int>,
    val stated: Boolean,
)

data class RenewedTable(
    val rows: List<List<String>>,
    val previous: DocumentPeriod,
    val period: DocumentPeriod,
    val cleared: List<String>,
    val kept: List<String>,

    val shifted: Int,
)

const val MIN_PERIOD_DAYS = 5

fun readPeriod(rows: List<List<String>>): PeriodReading? {
    val width = rows.maxOfOrNull { it.size } ?: return null
    for (c in 0 until width) {
        val dated = rows.indices.mapNotNull { r ->
            rows[r].getOrNull(c)?.let(::tableDate)?.let { r to it }
        }
        val days = dated.map { it.second }.distinct().sorted()
        if (days.size < MIN_PERIOD_DAYS) continue

        if (ChronoUnit.DAYS.between(days.first(), days.last()) + 1 != days.size.toLong()) continue
        val calendar = DocumentPeriod(days.first(), days.last())
        val stated = statedPeriod(rows, calendar)
        return PeriodReading(stated ?: calendar, c, dated.map { it.first }, stated != null)
    }
    return null
}

fun renewPeriod(rows: List<List<String>>): RenewedTable? {
    val reading = readPeriod(rows) ?: return null
    val previous = reading.period
    val out = rows.map { it.toMutableList() }
    var shifted = 0
    for (r in reading.dayRows) {
        val cell = out[r].getOrNull(reading.dateColumn) ?: continue
        val date = tableDate(cell) ?: continue
        out[r][reading.dateColumn] = dateLike(cell, date.plusDays(previous.days))
        shifted++
    }
    val cleared = mutableListOf<String>()
    val kept = mutableListOf<String>()
    val width = rows.maxOf { it.size }
    for (c in 0 until width) {
        if (c == reading.dateColumn) continue

        val values = reading.dayRows.mapNotNull { r ->
            rows[r].getOrNull(c)?.let(::normConsensus)?.takeIf { it.isNotBlank() }
        }
        if (values.isEmpty()) continue
        val name = columnName(rows, reading.dayRows.min(), c)

        val counts = values.groupingBy { it }.eachCount()
        if (values.count { counts.getValue(it) > 1 } * 2 > values.size) {
            kept += name
            continue
        }
        reading.dayRows.forEach { r -> if (c < out[r].size) out[r][c] = "" }
        cleared += name
    }
    return RenewedTable(out.map { it.toList() }, previous, previous.next(), cleared, kept, shifted)
}

internal fun tableDate(cell: String): LocalDate? =
    DATE.matchEntire(styleCell(cell).value.trim())?.let(::dateOf)

internal fun dateLike(sample: String, date: LocalDate): String {
    val m = DATE.matchEntire(styleCell(sample).value.trim()) ?: return date.toString()
    val (day, sep, month, year) = m.destructured
    val y = if (year.length == 2) (date.year % 100).toString().padStart(2, '0') else date.year.toString()
    return date.dayOfMonth.toString().padStart(day.length, '0') + sep +
        date.monthValue.toString().padStart(month.length, '0') + sep + y
}

private fun statedPeriod(rows: List<List<String>>, calendar: DocumentPeriod): DocumentPeriod? {
    rows.forEach { row ->
        row.forEach { cell ->
            val found = DATE.findAll(styleCell(cell).value).mapNotNull(::dateOf).toList()
            if (found.size == 2) {
                val (from, to) = found
                val stated = DocumentPeriod(from, to)
                val covers = !from.isAfter(calendar.from) && !to.isBefore(calendar.to) && !from.isAfter(to)
                if (covers && calendar.days * 2 >= stated.days) return stated
            }
        }
    }
    return null
}

private fun columnName(rows: List<List<String>>, firstDayRow: Int, column: Int): String {
    for (r in (firstDayRow - 1) downTo 0) {
        val header = rows[r].getOrNull(column)?.let { styleCell(it).value.trim() }
        if (!header.isNullOrEmpty()) return header.take(MAX_COLUMN_NAME)
    }
    return "столбец ${column + 1}"
}

private fun dateOf(m: MatchResult): LocalDate? {
    val (day, _, month, year) = m.destructured
    val full = if (year.length == 2) 2000 + year.toInt() else year.toInt()
    return runCatching { LocalDate.of(full, month.toInt(), day.toInt()) }.getOrNull()
}

private val DATE = Regex("""(\d{1,2})([./])(\d{1,2})\2(\d{4}|\d{2})""")

private const val MAX_COLUMN_NAME = 40
