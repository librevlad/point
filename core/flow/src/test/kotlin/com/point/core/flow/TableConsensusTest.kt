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
}
