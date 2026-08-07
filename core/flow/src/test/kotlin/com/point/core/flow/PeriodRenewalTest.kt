package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeriodRenewalTest {

    private fun schedule(): List<List<String>> {
        val rows = mutableListOf(
            listOf("Захід", "Дата", "Час", "Відповідальний", "Підпис"),
        )
        (16..29).forEachIndexed { i, day ->
            rows += listOf(
                "Захід $day",
                "%02d.07.2026".format(day),
                if (i % 2 == 0) "8-00" else "17-30",
                if (i % 2 == 0) "Відповідальний 1" else "Відповідальний 2",
                "підпис $day",
            )
        }
        return rows
    }

    @Test
    fun `период графика — это его календарь дат`() {
        val reading = checkNotNull(readPeriod(schedule()))
        assertEquals(LocalDate.of(2026, 7, 16), reading.period.from)
        assertEquals(LocalDate.of(2026, 7, 29), reading.period.to)
        assertEquals(14L, reading.period.days)
        assertEquals("календарь — столбец «Дата»", 1, reading.dateColumn)
        assertEquals("дни периода — все строки под шапкой", 14, reading.dayRows.size)
        assertFalse("период выведен из календаря, а не назван словами", reading.stated)
    }

    @Test
    fun `реестр договоров периодом не является — между датами дыры`() {

        val registry = listOf(
            listOf("Рік", "Номер", "Дата", "Постачальник", "Ціна", "діє з"),
            listOf("2018", "—", "18.10.18", "—", "78.00", "24.10.18"),
            listOf("2018", "—", "11.10.18", "—", "73,00", "01.10.18"),
            listOf("2018", "—", "18.12.18", "—", "78.00", "17.12.18"),
            listOf("2018", "—", "18.10.18", "—", "78.00", "22.10.18"),
            listOf("2018", "—", "05.10.18", "—", "78.00", "18.10.18"),
            listOf("2018", "—", "18.10.18", "—", "78.00", "22.10.18"),
        )
        assertNull(readPeriod(registry))
        assertNull(renewPeriod(registry))
    }

    @Test
    fun `ведомость без календаря дат периода не получает — и действия тоже`() {

        val ledger = listOf(
            listOf("Арт.№", "Найменування", "Рота зв'язку"),
            listOf("11004", "Буряк столовий свіжий", "6,003"),
            listOf("11008", "Ікра кабачкова", "2,04 1,994"),
            listOf("11012", "Капуста білоголова свіжа", "14,674"),
            listOf("11019", "Картопля рання", "38,333"),
            listOf("11025", "Маслини без кісточки", "0,230"),
            listOf("11026", "Морква свіжа", "4,523"),
        )
        assertNull(readPeriod(ledger))
    }

    @Test
    fun `названный словами период сильнее выкладки по крайним датам`() {

        val rows = mutableListOf(
            listOf("Відомість за період 27.07.2026 - 02.08.2026", "", ""),
            listOf("Дата", "Видано", "Приймальник"),
        )
        (28..31).forEach { day -> rows += listOf("$day.07.2026", "$day кг", "Приймальник") }
        (1..2).forEach { day -> rows += listOf("0$day.08.2026", "$day кг", "Приймальник") }
        val reading = checkNotNull(readPeriod(rows))
        assertTrue("период назван словами", reading.stated)
        assertEquals(LocalDate.of(2026, 7, 27), reading.period.from)
        assertEquals(LocalDate.of(2026, 8, 2), reading.period.to)
        assertEquals("календарь начинается позже — и это не спор с документом", 6, reading.dayRows.size)
    }

    @Test
    fun `чужой срок в примечании периодом таблицы не становится`() {

        val rows = schedule().mapIndexed { i, row ->
            if (i == 1) listOf("Договір діє з 01.01.2026 по 31.12.2026") + row.drop(1) else row
        }
        val reading = checkNotNull(readPeriod(rows))
        assertFalse("годовой срок договора — не период графика", reading.stated)
        assertEquals(LocalDate.of(2026, 7, 16), reading.period.from)
        assertEquals(LocalDate.of(2026, 7, 29), reading.period.to)

        val renewed = checkNotNull(renewPeriod(rows))
        assertEquals(LocalDate.of(2026, 7, 30), renewed.period.from)
        assertEquals("сдвиг на две недели, а не на год", "30.07.2026", renewed.rows[1][1])
    }

    @Test
    fun `названный период принимается, пока календарь его заполняет`() {

        fun ledger(stated: String) = listOf(listOf(stated, "", "")) +
            listOf(listOf("Дата", "Видано", "Приймальник")) +
            (1..5).map { day -> listOf("0$day.09.2026", "$day кг", "Приймальник") }

        assertTrue(
            "пять дней заполняют десять — большинство",
            checkNotNull(readPeriod(ledger("Відомість за 01.09.2026 - 10.09.2026"))).stated,
        )
        assertFalse(
            "те же пять дней в одиннадцати — уже не про эту таблицу",
            checkNotNull(readPeriod(ledger("Відомість за 01.09.2026 - 11.09.2026"))).stated,
        )
    }

    @Test
    fun `время не дата — столбец времени календарём не становится`() {
        assertNull(tableDate("8-00"))
        assertNull(tableDate("17-30"))
        assertNull(tableDate("16.07"))
        assertEquals(LocalDate.of(2026, 7, 16), tableDate("16.07.2026"))
        assertEquals(LocalDate.of(2018, 10, 18), tableDate("18.10.18"))

        assertEquals(LocalDate.of(2018, 10, 18), tableDate("18.10.18⚠"))
    }

    @Test
    fun `график продлевается — даты сдвинуты, постоянная часть на месте`() {
        val renewed = checkNotNull(renewPeriod(schedule()))
        assertEquals(LocalDate.of(2026, 7, 30), renewed.period.from)
        assertEquals(LocalDate.of(2026, 8, 12), renewed.period.to)
        assertEquals("сдвинуты все четырнадцать дат", 14, renewed.shifted)

        assertEquals(listOf("Захід", "Дата", "Час", "Відповідальний", "Підпис"), renewed.rows[0])
        assertEquals(listOf("", "30.07.2026", "8-00", "Відповідальний 1", ""), renewed.rows[1])
        assertEquals(listOf("", "31.07.2026", "17-30", "Відповідальний 2", ""), renewed.rows[2])
        assertEquals(listOf("", "01.08.2026", "8-00", "Відповідальний 1", ""), renewed.rows[3])
        assertEquals("последний день нового периода", "12.08.2026", renewed.rows[14][1])

        assertEquals(listOf("Захід", "Підпис"), renewed.cleared)
        assertEquals(listOf("Час", "Відповідальний"), renewed.kept)
    }

    @Test
    fun `повторяющаяся графа остаётся — правило судит документ, а не имя столбца`() {

        val rows = schedule().mapIndexed { i, row ->
            if (i == 0) row else listOf("Перевірка приманок") + row.drop(1)
        }
        val renewed = checkNotNull(renewPeriod(rows))
        assertEquals(listOf("Підпис"), renewed.cleared)
        assertTrue("Захід" in renewed.kept)
        assertEquals("Перевірка приманок", renewed.rows[1][0])
    }

    @Test
    fun `случайно совпавшие два дня графу не спасают`() {

        val rows = schedule().mapIndexed { i, row ->
            if (i in 1..2) listOf("Перевірка приманок") + row.drop(1) else row
        }
        assertTrue("Захід" in checkNotNull(renewPeriod(rows)).cleared)
    }

    @Test
    fun `одна подмена ответственного постоянную часть не отменяет`() {

        val rows = schedule().mapIndexed { i, row ->
            if (i == 0) row
            else row.take(3) + listOf(if (i == 5) "Відповідальний 2" else "Відповідальний 1") + row.drop(4)
        }
        val renewed = checkNotNull(renewPeriod(rows))
        assertTrue("Відповідальний" in renewed.kept)
        assertEquals("Відповідальний 2", renewed.rows[5][3])
    }

    @Test
    fun `шапка и итоговая строка не трогаются вовсе`() {
        val rows = schedule() + listOf(listOf("Разом", "", "", "14 заходів", ""))
        val renewed = checkNotNull(renewPeriod(rows))
        assertEquals(listOf("Захід", "Дата", "Час", "Відповідальний", "Підпис"), renewed.rows.first())
        assertEquals(listOf("Разом", "", "", "14 заходів", ""), renewed.rows.last())
    }

    @Test
    fun `новая дата написана так же, как была написана старая`() {

        val rows = (1..6).map { day -> listOf("0$day/09/26", "запись $day") }
        val renewed = checkNotNull(renewPeriod(rows))
        assertEquals("07/09/26", renewed.rows[0][0])
        assertEquals("12/09/26", renewed.rows[5][0])
    }

    @Test
    fun `столбец, пустой за прошлый период, в очищенные не попадает`() {

        val rows = schedule().mapIndexed { i, row -> if (i == 0) row else row.dropLast(1) + "" }
        val renewed = checkNotNull(renewPeriod(rows))
        assertEquals(listOf("Захід"), renewed.cleared)
    }

    @Test
    fun `безымянному столбцу дают номер, а не молчание`() {
        val rows = (1..6).map { day -> listOf("0$day.09.2026", "запись $day") }
        val renewed = checkNotNull(renewPeriod(rows))
        assertEquals(listOf("столбец 2"), renewed.cleared)
    }

    @Test
    fun `новый период — следующий такой же, без зазора и нахлёста`() {
        val week = DocumentPeriod(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 2))
        assertEquals(7L, week.days)
        assertEquals(LocalDate.of(2026, 8, 3), week.next().from)
        assertEquals(LocalDate.of(2026, 8, 9), week.next().to)
        assertEquals("длина сохраняется", week.days, week.next().days)
    }

    @Test
    fun `четыре дня подряд — ещё не период`() {
        val rows = (1..4).map { day -> listOf("0$day.09.2026", "запись $day") }
        assertNull(readPeriod(rows))
    }

    @Test
    fun `пустая таблица периода не имеет`() {
        assertNull(readPeriod(emptyList()))
        assertNull(renewPeriod(emptyList()))
    }
}
