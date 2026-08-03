package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Продление документа на новый период (#224) — на данных, которые действительно проходили через
 * этот пайплайн.
 *
 * Что откуда взято:
 * - **график мероприятий** — документ из самой заявки #224: столбцы «Захід · Дата · Час ·
 *   Відповідальний · Підпис», даты 16.07.2026 … 29.07.2026, время «8-00 / 17-30», подпись в
 *   каждой строке. Дословны здесь **шапка, даты и время** — то, чем документ судится. Фамилии
 *   ответственных заменены на «Відповідальний 1/2»: правило корпуса не пускает данные владельца
 *   в репозиторий, а проверяется тут повторяемость столбца, и она от замены не меняется.
 * - **реестр договоров** — кадр 06 корпуса, как его прочитало «В Excel» (`tab-06/06.tsv`
 *   прогона 01.08.2026): шапка и столбец дат дословны, значения заменены прочерком по тому же
 *   правилу. Это отрицательный случай: даты есть, периода нет.
 * - **продовольственная ведомость** — кадр 23; строки взяты из эталона `tools/corpus/23.expected.tsv`,
 *   который уже лежит в репозитории, период «27.07.2026 - 02.08.2026» — из шапки того же кадра.
 */
class PeriodRenewalTest {

    /** График из #224: две недели подряд, время и ответственный повторяются, у графы «Захід» и
     *  подписи — своё значение на каждый день. */
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

    // --- Что считается периодом ---

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
        // Дословный столбец «Дата» кадра 06: 18.10.18, 11.10.18, 18.12.18, 18.10.18, 05.10.18,
        // 18.10.18. Дат больше пяти, но подряд идущих дней среди них нет — это список событий,
        // а не период, и «продлить» на реестре не предлагается вовсе.
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
        // Кадр 23: 35 строк товаров и семь подразделений в шапке — дней в таблице нет вовсе.
        // Период там написан в шапке документа, но «В Excel» выгружает табличную часть без
        // макета (жалоба владельца в #224), и до нас эта строка не доезжает.
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
        // Шапка ведомости владельца, дословно: «27.07.2026 - 02.08.2026». Если такая строка в
        // таблице есть и она накрывает календарь — границы берутся из неё: документ сам сказал,
        // за что он.
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
    fun `время не дата — столбец времени календарём не становится`() {
        assertNull(tableDate("8-00"))
        assertNull(tableDate("17-30"))
        assertNull(tableDate("16.07"))
        assertEquals(LocalDate.of(2026, 7, 16), tableDate("16.07.2026"))
        assertEquals(LocalDate.of(2018, 10, 18), tableDate("18.10.18"))
        // Пометка неуверенности — не часть значения: дата под ⚠ остаётся датой.
        assertEquals(LocalDate.of(2018, 10, 18), tableDate("18.10.18⚠"))
    }

    // --- Что считается заполняемым заново ---

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
        // Тот же график, но мероприятие каждый день одно и то же. Ничего про «Захід» не
        // зашито: повторяется — значит постоянная часть бланка, и он остаётся.
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
        // «Захід» у 12 дней свой, у двух совпал. Если бы правилом был единственный повтор,
        // записи прошлого периода уехали бы в новый бланк молча — цена дороже пустой графы.
        val rows = schedule().mapIndexed { i, row ->
            if (i in 1..2) listOf("Перевірка приманок") + row.drop(1) else row
        }
        assertTrue("Захід" in checkNotNull(renewPeriod(rows)).cleared)
    }

    @Test
    fun `одна подмена ответственного постоянную часть не отменяет`() {
        // Тринадцать дней — один ответственный, один день — другой. Столбец остаётся: это
        // бланк, где один раз подменили человека, а не графа, которую заполняют каждый день.
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
        // Двузначный год и косая черта — как в документе; наш формат документу не навязывается.
        val rows = (1..6).map { day -> listOf("0$day/09/26", "запись $day") }
        val renewed = checkNotNull(renewPeriod(rows))
        assertEquals("07/09/26", renewed.rows[0][0])
        assertEquals("12/09/26", renewed.rows[5][0])
    }

    @Test
    fun `столбец, пустой за прошлый период, в очищенные не попадает`() {
        // Подпись «В Excel» с фото не читает — графа приходит пустой. Сказать «очищено: Підпис»
        // о том, что и так было пусто, значит отчитаться о работе, которой не было.
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

    // --- Про будущее ничего не выдумывается ---

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
