package com.point.core.flow

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Продлить документ на новый период (#224).
 *
 * Живой случай: в мессенджер приходит фото заполненного бланка с подписью «нужна такая же
 * бумага на следующий период». Сегодня человек перепечатывает её руками. Здесь — то, что
 * делать за него **честно**: сдвинуть даты и очистить то, что заполняется заново, оставив
 * бланк как он есть.
 *
 * ### Что считается периодом
 *
 * Только то, что в таблице **написано**. Период — это столбец-календарь: даты идут день за
 * днём, без пропусков, минимум [MIN_PERIOD_DAYS] дней подряд. Такой столбец значит, что
 * документ **ведётся за период**, а не просто упоминает дату, — то же различие, которым
 * якорное поле схемы отделяет «документ ПРО отправление» от «в документе есть адрес» (#262).
 * Реестр договоров с датами 05.10, 11.10, 18.10, 18.12 календарём не является: между датами
 * дыры, и продлевать там нечего.
 *
 * Если в таблице период вдобавок **назван словами** («27.07.2026 - 02.08.2026» в шапке),
 * границы берутся из этой записи: документ сам сказал, за что он, и наша выкладка по крайним
 * датам календаря спорить с ним не вправе. Но назвать период недостаточно: пока не видно, что
 * к нему привязано, стирать нечего и действия нет — и запись обязана быть периодом **этой**
 * таблицы, а не любым сроком, который её накрывает ([statedPeriod]).
 *
 * ### Что считается заполняемым заново
 *
 * Правило одно, и оно читается по самому документу:
 *
 * > **Столбец, где почти у каждой даты своё значение, заполняется заново — он очищается.
 * > Столбец, где значения в большинстве своём повторяются у разных дат, — постоянная часть
 * > бланка, он остаётся.**
 *
 * На графике мероприятий это отделяет подпись и заполненную от руки графу (у каждого дня
 * своя) от времени «8-00 / 17-30» и фамилии ответственного (повторяются весь период). Слепого
 * «стереть такие-то столбцы» здесь нет: решает наблюдаемое повторение, а какие столбцы вышли
 * очищенными, действие называет человеку вслух ([RenewedTable.cleared]).
 *
 * Сам календарь не стирается, а **сдвигается**: даты — скелет документа, а не запись за
 * период. Всё, что стоит вне дней периода — шапка, заголовки, итоговая строка, — не трогается
 * вовсе.
 *
 * ### Чего здесь нет намеренно
 *
 * - **Ничего не выдумывается про будущее.** Новый период — следующий такой же, сразу за
 *   прошлым ([DocumentPeriod.next]); «на месяц вперёд» или «от сегодняшнего числа» — это уже
 *   догадка о намерении, а её высказывает человек, а не мы.
 * - **Дни, идущие строкой** (календарь в шапке, столбцы-дни под ним — табель, недельная
 *   ведомость) в этом срезе не читаются. Ограничение названо, а не забыто: разворот того же
 *   правила на строки — следующий шаг.
 * - **Периода, который живёт только в шапке документа**, здесь тоже нет: «В Excel» выгружает
 *   табличную часть без макета (жалоба владельца в #224), и до нас такая шапка не доезжает.
 */
data class DocumentPeriod(val from: LocalDate, val to: LocalDate) {

    /** Длина периода в днях, включая обе границы: 16.07–29.07 — это 14 дней. */
    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1

    /** Следующий такой же период — той же длины, сразу за этим, без зазора и нахлёста. */
    fun next(): DocumentPeriod = DocumentPeriod(from.plusDays(days), to.plusDays(days))
}

/**
 * Период, прочитанный в таблице, и то, чем он в ней держится.
 *
 * [dayRows] — строки, помеченные датой периода: только их правило и трогает. [stated] говорит,
 * что границы взяты из записи словами, а не выведены из крайних дат календаря.
 */
data class PeriodReading(
    val period: DocumentPeriod,
    val dateColumn: Int,
    val dayRows: List<Int>,
    val stated: Boolean,
)

/**
 * Таблица, продлённая на новый период, и рассказ о том, что с ней сделали.
 *
 * [cleared] и [kept] — названия столбцов по шапке (или «столбец N», если шапки нет). Очищенные
 * перечисляются не для красоты: стирание, о котором человек не знает, — это потеря, а не работа.
 */
data class RenewedTable(
    val rows: List<List<String>>,
    val previous: DocumentPeriod,
    val period: DocumentPeriod,
    val cleared: List<String>,
    val kept: List<String>,
    /** Сколько ячеек-дат сдвинуто. */
    val shifted: Int,
)

/** Меньше — не календарь: три-четыре подряд идущие даты бывают у чего угодно. */
const val MIN_PERIOD_DAYS = 5

/**
 * Читает период таблицы, или `null` — периода нет и действие не предлагается вовсе.
 *
 * Столбец-календарь ищется слева направо; первый подошедший и есть календарь документа.
 */
fun readPeriod(rows: List<List<String>>): PeriodReading? {
    val width = rows.maxOfOrNull { it.size } ?: return null
    for (c in 0 until width) {
        val dated = rows.indices.mapNotNull { r ->
            rows[r].getOrNull(c)?.let(::tableDate)?.let { r to it }
        }
        val days = dated.map { it.second }.distinct().sorted()
        if (days.size < MIN_PERIOD_DAYS) continue
        // Пропущенный день — не период, а список событий: между датами реестра договоров
        // дыры в недели, и «продлить» там нечего.
        if (ChronoUnit.DAYS.between(days.first(), days.last()) + 1 != days.size.toLong()) continue
        val calendar = DocumentPeriod(days.first(), days.last())
        val stated = statedPeriod(rows, calendar)
        return PeriodReading(stated ?: calendar, c, dated.map { it.first }, stated != null)
    }
    return null
}

/**
 * Продлевает таблицу на следующий период, или `null` — периода в ней нет.
 *
 * Правило целиком — в [DocumentPeriod]: календарь сдвигается, столбец со своим значением у
 * каждой даты очищается, повторяющийся остаётся, остальное не трогается вовсе.
 */
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
        // Свёртка чтений ([normConsensus]) — та же, которой судится согласие моделей: «8-00»
        // и «8‑00» повторяются, а не спорят, и наше собственное оформление не должно решать,
        // считать ли столбец постоянным.
        val values = reading.dayRows.mapNotNull { r ->
            rows[r].getOrNull(c)?.let(::normConsensus)?.takeIf { it.isNotBlank() }
        }
        if (values.isEmpty()) continue // в периоде столбец и так пуст — стирать нечего
        val name = columnName(rows, reading.dayRows.min(), c)
        // Большинство, а не единственный повтор: два дня, где мероприятие случайно совпало, не
        // делают графу постоянной частью бланка, а одна подмена ответственного не делает
        // фамилию записью за день. Ошибаться приходится в обе стороны, и порог посередине —
        // единственное, что судит одинаково честно оба края.
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

/**
 * Дата ячейки — и только она: ячейка целиком должна быть датой.
 *
 * Разделитель — точка или косая черта, но не тире: «8-00» и «17-30» на графике мероприятий —
 * это время, и принять их за даты значило бы объявить календарём столбец времени.
 */
internal fun tableDate(cell: String): LocalDate? =
    DATE.matchEntire(styleCell(cell).value.trim())?.let(::dateOf)

/**
 * Новая дата, написанная **как была написана старая**: тот же разделитель, та же ширина дня,
 * месяца и года. Документ, где половина дат стала выглядеть иначе, читается как чужой.
 */
internal fun dateLike(sample: String, date: LocalDate): String {
    val m = DATE.matchEntire(styleCell(sample).value.trim()) ?: return date.toString()
    val (day, sep, month, year) = m.destructured
    val y = if (year.length == 2) (date.year % 100).toString().padStart(2, '0') else date.year.toString()
    return date.dayOfMonth.toString().padStart(day.length, '0') + sep +
        date.monthValue.toString().padStart(month.length, '0') + sep + y
}

/**
 * Период, названный в таблице словами: две даты в одной ячейке — «27.07.2026 - 02.08.2026»,
 * «з 27.07.2026 по 02.08.2026».
 *
 * Запись обязана **накрывать** календарь — и он обязан её **заполнять**: большинство дней
 * названного периода должны быть днями, которые в таблице действительно есть.
 *
 * Второе условие не украшение первого. Накрыть две недели графика может что угодно: срок
 * договора «діє з 01.01.2026 по 31.12.2026», строчкой в примечании, накрывает их полностью — и
 * по одному накрытию документ уезжал на **год** вперёд вместо двух недель, а человеку это
 * сообщалось как готовый бланк на 2027-й. Чужой срок, упомянутый в таблице, периодом таблицы не
 * становится: период — тот, по которому она ведётся.
 */
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

/** Название столбца по шапке: ближайшая непустая ячейка над днями периода. */
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

/** Дата, как её пишут в этих документах: `16.07.2026`, `05.10.18`, `1/9/2026`. */
private val DATE = Regex("""(\d{1,2})([./])(\d{1,2})\2(\d{4}|\d{2})""")

/** Длиннее названия столбцов в рассказе человеку не нужны — это подпись, а не текст. */
private const val MAX_COLUMN_NAME = 40
