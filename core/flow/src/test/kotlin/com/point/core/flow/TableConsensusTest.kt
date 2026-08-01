package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #200 ocr++ multi-model consensus — several vision models read the same table; [reconcile] votes each
 * cell. Cells where the models agree pass through clean; cells where they disagree get the plurality
 * value, a ⚠ flag, and the distinct readings as candidates (the owner then picks — «шаманство с
 * ячейками»). Pure and model-free, so it is unit-tested directly.
 */
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
        assertEquals("5⚠", c.rows[1][1])                 // plurality 5 of {5,5,8}, flagged uncertain
        assertEquals(listOf("5", "8"), c.candidates[1 to 1])
        assertTrue(c.candidates[1 to 0] == null)          // the id cell agreed
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

    // --- Выравнивание по содержимому (#294) ---

    @Test
    fun `модель, пропустившая заголовок, не сдвигает голосование`() {
        // Ровно случай из дыма: одна модель вернула шапку, другая начала со строки данных.
        val withHeader = listOf(header, listOf("1", "5"), listOf("2", "8"))
        val without = listOf(listOf("1", "5"), listOf("2", "8"))

        val c = reconcile(listOf(withHeader, without))

        assertEquals(listOf(header, listOf("1", "5"), listOf("2", "8")), c.rows)
        assertTrue("сдвиг больше не рождает ложный спор", c.candidates.isEmpty())
    }

    @Test
    fun `строка, которую увидела только одна модель, встаёт на своё место`() {
        val full = listOf(header, listOf("1", "5"), listOf("2", "8"), listOf("3", "9"))
        val skipped = listOf(header, listOf("1", "5"), listOf("3", "9")) // пропущена середина

        val c = reconcile(listOf(full, skipped))

        assertEquals(listOf(header, listOf("1", "5"), listOf("2", "8"), listOf("3", "9")), c.rows)
        assertTrue(c.candidates.isEmpty())
    }

    @Test
    fun `настоящее расхождение внутри выровненной строки по-прежнему спор`() {
        val a = listOf(header, listOf("1", "5"))
        val b = listOf(listOf("1", "8")) // без шапки И с другим значением

        val c = reconcile(listOf(a, b))

        assertEquals(header, c.rows[0])
        assertEquals("5⚠", c.rows[1][1])
        assertEquals(listOf("5", "8"), c.candidates[1 to 1])
    }

    @Test
    fun `находка только второй модели становится своей строкой, а не спором`() {
        val a = listOf(header, listOf("1", "5"))
        val b = listOf(header, listOf("1", "5"), listOf("2", "8"))

        val c = reconcile(listOf(a, b))

        assertEquals(3, c.rows.size)
        assertEquals(listOf("2", "8"), c.rows[2])
        assertTrue(c.candidates.isEmpty())
    }

    @Test
    fun `ragged tables align by index and vote on present values`() {
        val a = listOf(header, listOf("1", "5"), listOf("2", "9"))
        val b = listOf(header, listOf("1", "5")) // shorter — no row 2
        val c = reconcile(listOf(a, b))
        assertEquals("9", c.rows[2][1])            // only a has it → taken as-is, not flagged
        assertTrue(c.candidates.isEmpty())
    }

    @Test
    fun `сильно разошедшаяся строка остаётся одной строкой и голосуется (ревью #294)`() {
        // Разорванная надвое, она голосовалась в одиночку: спор исчезал, ⚠ не ставился,
        // а в файле появлялась лишняя строка — консенсус выключался там, где он нужнее всего.
        val a = listOf(header, listOf("1", "5"), listOf("Итого", "42"))
        val b = listOf(header, listOf("1", "5"), listOf("Всего", "48"))

        val c = reconcile(listOf(a, b))

        assertEquals("три строки, а не четыре", 3, c.rows.size)
        assertTrue("спор виден", c.rows[2].any { it.contains("⚠") })
        assertEquals(listOf("42", "48"), c.candidates[2 to 1])
    }

    @Test
    fun `узкая таблица с повторяющимся столбцом не склеивает разные строки (ревью #294)`() {
        // «1|5» и «2|5» совпадали ровно наполовину — половины мало, чтобы звать их одной строкой.
        val a = listOf(header, listOf("1", "5"), listOf("2", "5"))
        val b = listOf(header, listOf("1", "5"), listOf("2", "5"))

        val c = reconcile(listOf(a, b))

        assertEquals(3, c.rows.size)
        assertEquals(listOf("1", "5"), c.rows[1])
        assertEquals(listOf("2", "5"), c.rows[2])
        assertTrue(c.candidates.isEmpty())
    }

    // -- ведомость владельца: строки узнают друг друга по артикулу (#294) --

    @Test
    fun `рукописные пометки не раздваивают строку, если у неё есть артикул`() {
        // Эталонный кадр владельца: печатный бланк с артикулами, поверх — пометки ручкой.
        // Одна модель отдаёт их как часть ячеек, другая — отдельной строкой; по похожести
        // строки разъезжались, и в файле появлялась строка-фантом.
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
            listOf("11006", "Горошок зелений", "0,72 0,883"),   // пометка ручкой в ячейке
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
        assertTrue("находка второй модели на месте", c.rows.any { it.take(2) == listOf("11010", "Кабачки") })
    }

    @Test
    fun `артикул опознаётся, даже если чтения разошлись колонками`() {
        // Живой прогон ведомости: одно чтение отдало артикул первой колонкой, другое — второй
        // (первую заняла пустая колонка бланка). Ключ, искомый по совпадающему индексу, не
        // находился, и строки двоились — в файле повторялись почти все артикулы.
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
        // Строки сошлись, а ячейки внутри них — нет: пустая колонка бланка сдвигала чтение на
        // один столбец, и «Буряк» спорил с «11004», а «6,003» — с «Буряк». Столбцы каждой строки
        // теперь тоже сходятся по содержимому, поэтому спор остаётся ровно там, где он есть.
        assertEquals(listOf("11004", "Буряк", "6,003"), c.rows[0])
        assertEquals("сдвиг колонки — не спор", emptySet<Pair<Int, Int>>(), c.candidates.keys - setOf(1 to 2))
        assertEquals("пометка ручкой — спор настоящий", listOf("0,883", "0,72 0,883"), c.candidates[1 to 2])
    }

    // -- ячейки внутри строки тоже едут (#294: в файле 385 помеченных ячеек из 448 непустых) --

    @Test
    fun `пропущенный столбец не делает спорной каждую ячейку после него`() {
        // Дословная строка ведомости из живого прогона: второе чтение пропустило узкий столбец
        // бланка («0,087») и дописало хвост, поэтому по индексу спорной становилась каждая
        // ячейка после пропуска — в дропдауне рядом с «0,087» стояло «1,375», значение соседа.
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
        // Спорят ровно те ячейки, где чтения правда разошлись.
        assertEquals(listOf("0,120", "0,125"), c.candidates[1 to 7])
        assertEquals(listOf(1 to 7, 1 to 9), c.candidates.keys.toList())
        assertTrue("хвост второго чтения не потерян", c.rows[1].contains("0,875"))
    }

    @Test
    fun `настоящее расхождение колонок не выравнивается ради красивого числа`() {
        // Обратная сторона: если модели разошлись в значении, а не в раскладке, двигать ячейки
        // не на чем — раскладка по содержимому не выигрывает у индексной, и спор остаётся.
        val a = listOf(header, listOf("1", "5"), listOf("2", "9"))
        val b = listOf(header, listOf("1", "5"), listOf("2", "7"))

        val c = reconcile(listOf(a, b))

        assertEquals(3, c.rows.size)
        assertEquals(listOf("9", "7"), c.candidates[2 to 1])
    }

    // -- ложная склейка строк рождала ложный спор (#294) --

    @Test
    fun `разные артикулы — разные строки документа, а не два чтения одной`() {
        // Живой прогон: замена соседних односторонних строк склеивала строку «11401 Пластівці»
        // с чужой строкой «11029 Огірки», и человек получал дропдаун из двух разных товаров.
        val a = listOf(header, listOf("11401", "Пластівці вівсяні"))
        val b = listOf(listOf("11029", "Огірки консервовані"))

        val c = reconcile(listOf(a, b))

        assertEquals("строки остались своими", 3, c.rows.size)
        assertTrue("спора из ничего нет", c.candidates.isEmpty())
        assertTrue(c.rows.none { row -> row.any { it.contains("⚠") } })
    }

    @Test
    fun `шапка второго чтения не становится вариантом значения`() {
        // Ровно то, что владелец видел в файле: «0,831» предлагалось заменить на «Рота зв'язку».
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
        // Право вето — про идентификатор, а не про «всё непохожее»: «Итого/42» против
        // «Всего/48» идентификатора не несёт, и спор о числе остаётся виден.
        val a = listOf(header, listOf("1", "5"), listOf("Итого", "42"))
        val b = listOf(header, listOf("1", "5"), listOf("Всего", "48"))

        val c = reconcile(listOf(a, b))

        assertEquals(3, c.rows.size)
        assertEquals(listOf("42", "48"), c.candidates[2 to 1])
    }

    // -- отсутствие и пометка ручкой --

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
        // Разбор гипотезы: «0,72 0,883» против «0,883» — не формат и не шум. Одна модель увидела
        // рукописную правку поверх печати, другая нет; выбор между ними — это ровно то, ради
        // чего дропдаун и существует, поэтому пометка тут честная и не прячется.
        val printed = listOf(header, listOf("11006", "0,883"))
        val withPen = listOf(header, listOf("11006", "0,72 0,883"))

        val c = reconcile(listOf(printed, withPen))

        assertTrue(c.rows[1][1].contains("⚠"))
        assertEquals(listOf("0,883", "0,72 0,883"), c.candidates[1 to 1])
    }
}
