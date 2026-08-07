package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableConsensusTest {

    private val header = listOf("№", "Кількість")

    @Test
    fun `unanimous cells pass through clean with no candidates`() {
        val t = listOf(header, listOf("1", "5"), listOf("2", "8"))
        val c = reconcile(listOf(t, t, t))
        assertEquals(listOf(header, listOf("1", "5"), listOf("2", "8")), c.rows)
        assertTrue(c.candidates.isEmpty())
    }

    @Test
    fun `a disagreed cell gets the plurality value, a flag and the distinct candidates`() {
        val a = listOf(header, listOf("1", "5"))
        val b = listOf(header, listOf("1", "5"))
        val d = listOf(header, listOf("1", "8"))
        val c = reconcile(listOf(a, b, d))
        assertEquals("5⚠", c.rows[1][1])
        assertEquals(listOf("5", "8"), c.candidates[1 to 1])
        assertTrue(c.candidates[1 to 0] == null)
    }

    @Test
    fun `agreement ignores spacing and dashes`() {
        val a = listOf(header, listOf("1", "0,72 0,883"))
        val b = listOf(header, listOf("1", "0,72  0,883"))
        val c = reconcile(listOf(a, b))
        assertTrue("spacing-only diff is agreement", c.candidates.isEmpty())
    }

    @Test
    fun `a single table passes through unchanged`() {
        val a = listOf(header, listOf("1", "5"))
        assertEquals(a, reconcile(listOf(a)).rows)
    }

    @Test
    fun `модель, пропустившая заголовок, не сдвигает голосование`() {

        val withHeader = listOf(header, listOf("1", "5"), listOf("2", "8"))
        val without = listOf(listOf("1", "5"), listOf("2", "8"))

        val c = reconcile(listOf(withHeader, without))

        assertEquals(listOf("1", "5"), c.rows[1])
        assertEquals(listOf("2", "8"), c.rows[2])
        assertTrue("сдвиг больше не рождает ложный спор", c.candidates.isEmpty())
    }

    @Test
    fun `строка, которую увидела только одна модель, встаёт на своё место`() {
        val full = listOf(header, listOf("1", "5"), listOf("2", "8"), listOf("3", "9"))
        val skipped = listOf(header, listOf("1", "5"), listOf("3", "9"))

        val c = reconcile(listOf(full, skipped))

        assertEquals(4, c.rows.size)
        assertEquals(listOf("1", "5"), c.rows[1])
        assertEquals(listOf("3", "9"), c.rows[3])

        assertTrue(c.rows[2].all { "⚠" in it })
        assertTrue(c.candidates.isEmpty())
    }

    @Test
    fun `настоящее расхождение внутри выровненной строки по-прежнему спор`() {
        val a = listOf(header, listOf("1", "5"))
        val b = listOf(listOf("1", "8"))

        val c = reconcile(listOf(a, b))

        assertEquals(header, c.rows[0].map { it.removeSuffix("⚠") })
        assertEquals("5⚠", c.rows[1][1])
        assertEquals(listOf("5", "8"), c.candidates[1 to 1])
    }

    @Test
    fun `находка только второй модели становится своей строкой, а не спором`() {
        val a = listOf(header, listOf("1", "5"))
        val b = listOf(header, listOf("1", "5"), listOf("2", "8"))

        val c = reconcile(listOf(a, b))

        assertEquals(3, c.rows.size)

        assertEquals(listOf("2⚠", "8⚠"), c.rows[2])
        assertTrue(c.candidates.isEmpty())
    }

    @Test
    fun `ragged tables align by index and vote on present values`() {
        val a = listOf(header, listOf("1", "5"), listOf("2", "9"))
        val b = listOf(header, listOf("1", "5"))
        val c = reconcile(listOf(a, b))
        assertEquals("9⚠", c.rows[2][1])
        assertTrue(c.candidates.isEmpty())
    }

    @Test
    fun `сильно разошедшаяся строка остаётся одной строкой и голосуется (ревью #294)`() {

        val a = listOf(header, listOf("1", "5"), listOf("Итого", "42"))
        val b = listOf(header, listOf("1", "5"), listOf("Всего", "48"))

        val c = reconcile(listOf(a, b))

        assertEquals("три строки, а не четыре", 3, c.rows.size)
        assertTrue("спор виден", c.rows[2].any { it.contains("⚠") })
        assertEquals(listOf("42", "48"), c.candidates[2 to 1])
    }

    @Test
    fun `узкая таблица с повторяющимся столбцом не склеивает разные строки (ревью #294)`() {

        val a = listOf(header, listOf("1", "5"), listOf("2", "5"))
        val b = listOf(header, listOf("1", "5"), listOf("2", "5"))

        val c = reconcile(listOf(a, b))

        assertEquals(3, c.rows.size)
        assertEquals(listOf("1", "5"), c.rows[1])
        assertEquals(listOf("2", "5"), c.rows[2])
        assertTrue(c.candidates.isEmpty())
    }

    @Test
    fun `чтения, говорящие о разных таблицах, не смешиваются`() {
        val first = listOf(
            listOf("11004", "Буряк"), listOf("11006", "Горошок"), listOf("11008", "Ікра"),
            listOf("11012", "Капуста"), listOf("11019", "Картопля"), listOf("11024", "Кукурудза"),
            listOf("11025", "Маслини"), listOf("11026", "Морква"), listOf("11028", "Огірки"),
        )
        val other = listOf(
            listOf("11004", "Буряк"), listOf("1162", "Паштет"), listOf("1165", "Йогурт"),
            listOf("1167", "Масло"), listOf("1168", "Консерва"), listOf("1169", "Паштет м'ясний"),
            listOf("1205", "Яйця"), listOf("1207", "Сир"), listOf("1209", "Молоко"),
        )

        val c = reconcile(listOf(first, other))

        assertEquals("отдано одно чтение целиком", first, c.rows)
        assertTrue("без пометок и без вариантов", c.candidates.isEmpty())
        assertTrue(c.rows.none { row -> row.any { "⚠" in it } })
    }

    @Test
    fun `при общем костяке смесь остаётся, а дописанное помечено`() {
        val base = listOf(
            listOf("11004", "Буряк"), listOf("11006", "Горошок"), listOf("11008", "Ікра"),
            listOf("11012", "Капуста"), listOf("11019", "Картопля"), listOf("11024", "Кукурудза"),
            listOf("11025", "Маслини"), listOf("11026", "Морква"), listOf("11028", "Огірки"),
        )
        val withTail = base + listOf(listOf("1162", "Паштет"))

        val c = reconcile(listOf(base, withTail))

        assertEquals(10, c.rows.size)
        assertTrue("костяк чист", c.rows.take(9).none { row -> row.any { "⚠" in it } })
        assertTrue("дописанное помечено", c.rows[9].all { "⚠" in it })
    }

    private fun ledger(vararg values: String) = listOf(
        listOf("Арт.", "Товар", "До видачі"),
    ) + values.mapIndexed { i, v -> listOf("110" + (10 + i), "Товар " + i, v) }

    @Test
    fun `при расхождении остаётся чтение, ближайшее к остальным, а не первое по списку`() {

        val drifted = ledger("9,9", "1,1", "2,2", "3,3", "4,4", "5,5", "6,6", "7,7", "8,8")
        val goodA = ledger("1,1", "2,2", "3,3", "4,4", "5,5", "6,6", "7,7", "8,8", "9,9")
        val goodB = ledger("1,1", "2,2", "3,3", "4,4", "5,5", "6,6", "7,7", "8,8", "9,9")

        val c = reconcile(listOf(drifted, goodA, goodB))

        assertEquals(3, c.sources)
        assertTrue("расхождение в значениях видно", c.candidates.isNotEmpty())

        assertTrue(c.rows[1][2].startsWith("1,1"))
    }

    @Test
    fun `несостоявшийся свод называет число подтвердивших чтений`() {
        val first = listOf(
            listOf("11004", "Буряк"), listOf("11006", "Горошок"), listOf("11008", "Ікра"),
            listOf("11012", "Капуста"), listOf("11019", "Картопля"), listOf("11024", "Кукурудза"),
            listOf("11025", "Маслини"), listOf("11026", "Морква"), listOf("11028", "Огірки"),
        )
        val other = listOf(
            listOf("1162", "Паштет"), listOf("1165", "Йогурт"), listOf("1167", "Масло"),
            listOf("1168", "Консерва"), listOf("1169", "Сир"), listOf("1205", "Яйця"),
            listOf("1207", "Молоко"), listOf("1209", "Масло 2"), listOf("1211", "Хліб"),
        )

        val c = reconcile(listOf(first, other))

        assertEquals("свод не состоялся — за таблицей одно чтение", 1, c.sources)
        assertEquals(first, c.rows)
    }

    @Test
    fun `состоявшийся свод называет все чтения`() {
        val t = ledger("1,1", "2,2", "3,3", "4,4", "5,5", "6,6", "7,7", "8,8", "9,9")

        assertEquals(2, reconcile(listOf(t, t)).sources)
        assertEquals(1, reconcile(listOf(t)).sources)
    }

    @Test
    fun `рукописные пометки не раздваивают строку, если у неё есть артикул`() {

        val printed = listOf(
            listOf("Арт.", "Найменування", "До видачі"),
            listOf("11004", "Буряк столовий", "6,003"),
            listOf("11006", "Горошок зелений", "0,883"),
            listOf("11008", "Ікра кабачкова", "1,994"),
            listOf("11012", "Капуста білоголова", "14,674"),
            listOf("11019", "Картопля рання", "38,333"),
            listOf("11024", "Кукурудза", "0,882"),
        )
        val withPen = listOf(
            listOf("Арт.", "Найменування", "До видачі"),
            listOf("11004", "Буряк столовий", "6,003"),
            listOf("11006", "Горошок зелений", "0,72 0,883"),
            listOf("11008", "Ікра кабачкова", "2,04 1,994"),
            listOf("11012", "Капуста білоголова", "0,51 14,674"),
            listOf("11019", "Картопля рання", "38,333"),
            listOf("11024", "Кукурудза", "1,0 0,882"),
        )

        val c = reconcile(listOf(printed, withPen))

        assertEquals("строк ровно столько же, сколько в бланке", 7, c.rows.size)
        assertEquals(listOf("11008", "Ікра кабачкова"), c.rows[3].take(2))
        assertTrue("расхождение видно как спор", c.rows[3][2].contains("⚠"))
    }

    @Test
    fun `строка, которую увидела только одна модель, встаёт по своему артикулу`() {
        val short = listOf(
            listOf("Арт.", "Товар"), listOf("11004", "Буряк"), listOf("11006", "Горошок"),
            listOf("11008", "Ікра"), listOf("11012", "Капуста"), listOf("11019", "Картопля"),
        )
        val full = listOf(
            listOf("Арт.", "Товар"), listOf("11004", "Буряк"), listOf("11006", "Горошок"),
            listOf("11008", "Ікра"), listOf("11010", "Кабачки"), listOf("11012", "Капуста"),
            listOf("11019", "Картопля"),
        )

        val c = reconcile(listOf(short, full))

        assertEquals(7, c.rows.size)

        assertTrue(
            "находка второй модели на месте",
            c.rows.any { it.take(2).map { v -> v.removeSuffix("⚠") } == listOf("11010", "Кабачки") },
        )
        assertTrue(c.rows.first { "11010" in it.first() }.all { "⚠" in it })
    }

    @Test
    fun `артикул опознаётся, даже если чтения разошлись колонками`() {

        val first = listOf(
            listOf("11004", "Буряк", "6,003"), listOf("11006", "Горошок", "0,883"),
            listOf("11008", "Ікра", "1,994"), listOf("11012", "Капуста", "14,674"),
            listOf("11019", "Картопля", "38,333"), listOf("11024", "Кукурудза", "0,882"),
        )
        val shifted = listOf(
            listOf("", "11004", "Буряк", "6,003"), listOf("", "11006", "Горошок", "0,72 0,883"),
            listOf("", "11008", "Ікра", "1,994"), listOf("", "11012", "Капуста", "14,674"),
            listOf("", "11019", "Картопля", "38,333"), listOf("", "11024", "Кукурудза", "0,882"),
        )

        val c = reconcile(listOf(first, shifted))

        assertEquals("строк столько же, сколько в бланке", 6, c.rows.size)

        assertEquals(listOf("11004", "Буряк", "6,003"), c.rows[0])
        assertEquals("сдвиг колонки — не спор", emptySet<Pair<Int, Int>>(), c.candidates.keys - setOf(1 to 2))
        assertEquals("пометка ручкой — спор настоящий", listOf("0,883", "0,72 0,883"), c.candidates[1 to 2])
    }

    @Test
    fun `пропущенный столбец не делает спорной каждую ячейку после него`() {

        val head = listOf("Арт.", "Найменування", "1", "2", "3", "4", "5", "6", "7", "8")
        val name = "Крупа горохова колота"
        val a = listOf(
            head,
            listOf("11404", name, "2,875", "0,250", "1,000", "0,087", "1,375", "0,120", "0,625", "0,054"),
        )
        val b = listOf(
            head,
            listOf("11404", name, "2,875", "0,250", "1,000", "1,375", "0,125", "0,625", "0,875", "0,875"),
        )

        val c = reconcile(listOf(a, b))

        assertEquals("строка одна", 2, c.rows.size)
        assertEquals("пропуск второго чтения — отсутствие, а не спор", "0,087", c.rows[1][5])
        assertEquals("совпавшее значение встретилось со своим", "1,375", c.rows[1][6])

        assertEquals(listOf("0,120", "0,125"), c.candidates[1 to 7])
        assertEquals(listOf(1 to 7, 1 to 9), c.candidates.keys.toList())
        assertTrue("хвост второго чтения не потерян", c.rows[1].contains("0,875"))
    }

    @Test
    fun `настоящее расхождение колонок не выравнивается ради красивого числа`() {

        val a = listOf(header, listOf("1", "5"), listOf("2", "9"))
        val b = listOf(header, listOf("1", "5"), listOf("2", "7"))

        val c = reconcile(listOf(a, b))

        assertEquals(3, c.rows.size)
        assertEquals(listOf("9", "7"), c.candidates[2 to 1])
    }

    @Test
    fun `разные артикулы — разные строки документа, а не два чтения одной`() {

        val a = listOf(header, listOf("11401", "Пластівці вівсяні"))
        val b = listOf(listOf("11029", "Огірки консервовані"))

        val c = reconcile(listOf(a, b))

        assertEquals("строки остались своими", 3, c.rows.size)
        assertTrue("спора из ничего нет", c.candidates.isEmpty())

        assertTrue(c.rows.all { row -> row.none { "Огірки" in it && "Пластівці" in it } })
    }

    @Test
    fun `шапка второго чтения не становится вариантом значения`() {

        val a = listOf(listOf("Арт.", "Товар", "Кількість"), listOf("11026", "Морква свіжа", "0,831"))
        val b = listOf(listOf("Арт. №", "Наименование", "Рота зв'язку"))

        val c = reconcile(listOf(a, b))

        assertTrue(
            "подпись столбца не спорит со значением",
            c.candidates.values.none { it.any { v -> v.contains("Рота") } },
        )
    }

    @Test
    fun `строка без артикула по-прежнему голосуется как одна`() {

        val a = listOf(header, listOf("1", "5"), listOf("Итого", "42"))
        val b = listOf(header, listOf("1", "5"), listOf("Всего", "48"))

        val c = reconcile(listOf(a, b))

        assertEquals(3, c.rows.size)
        assertEquals(listOf("42", "48"), c.candidates[2 to 1])
    }

    @Test
    fun `строки узнают друг друга по артикулу, даже если разбиты на разное число ячеек`() {
        val perWord = listOf(
            listOf("", "1.", "304702", "Кахель", "д/стіни", "ELBA", "25х40", "86205", "кв.м.", "42"),
            listOf("", "2.", "395601", "Грес", "BRANTWOOD", "GREY", "18,5x59,8", "9", "кв.м.", "14"),
        )
        val grouped = listOf(
            listOf("1.", "304702", "Кахель д/стіни ELBA 25х40", "86205", "кв.м.", "42"),
            listOf("2.", "395601", "Грес BRANTWOOD GREY 18,5x59,8", "9", "кв.м.", "14"),
        )

        val c = reconcile(listOf(perWord, grouped))

        assertEquals("строк столько же, сколько в документе", 2, c.rows.size)
        assertEquals("за таблицей и правда два чтения", 2, c.sources)
        assertTrue("артикул не задвоился", c.rows.count { row -> row.any { "304702" in it } } == 1)
    }

    @Test
    fun `артикул, встреченный в чтении дважды, строку не опознаёт`() {
        val doubled = listOf(
            listOf("", "1.", "304702", "Кахель", "д/стіни", "ELBA", "25х40"),
            listOf("", "2.", "304702", "Грес", "BRANTWOOD", "GREY", "18,5x59,8"),
        )
        val grouped = listOf(
            listOf("1.", "304702", "Кахель д/стіни ELBA 25х40"),
            listOf("2.", "395601", "Грес BRANTWOOD GREY 18,5x59,8"),
        )

        val c = reconcile(listOf(doubled, grouped))

        assertEquals("двусмысленный артикул строки не сводит", 4, c.rows.size)
    }

    @Test
    fun `пустая ячейка против непустой — отсутствие, а не спор`() {
        val a = listOf(header, listOf("11004", "6,003"))
        val b = listOf(header, listOf("11004", ""))

        val c = reconcile(listOf(a, b))

        assertEquals("6,003", c.rows[1][1])
        assertTrue(c.candidates.isEmpty())
    }

    @Test
    fun `пометка ручкой, которую увидела одна модель, — спор, и он остаётся`() {

        val printed = listOf(header, listOf("11006", "0,883"))
        val withPen = listOf(header, listOf("11006", "0,72 0,883"))

        val c = reconcile(listOf(printed, withPen))

        assertTrue(c.rows[1][1].contains("⚠"))
        assertEquals(listOf("0,883", "0,72 0,883"), c.candidates[1 to 1])
    }

    @Test
    fun `строка, которую видело одно чтение из двух, помечена — отсутствие не согласие`() {
        val both = listOf(header, listOf("11004", "6,003"))
        val invented = both + listOf(listOf("1162", "Паштет м'ясний"))

        val c = reconcile(listOf(both, invented))

        assertEquals(3, c.rows.size)
        assertTrue("выдуманная строка помечена", c.rows[2].all { it.isBlank() || "⚠" in it })
        assertTrue("строка обоих чтений чиста", c.rows[1].none { "⚠" in it })

        assertTrue(c.candidates.keys.none { it.first == 2 })
    }
}
