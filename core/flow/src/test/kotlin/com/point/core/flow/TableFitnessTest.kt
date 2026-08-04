package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порог годности (#493): плохое чтение не должно превращаться в плохой файл.
 *
 * Живой случай — ведомость владельца: из 23 строк листа одиннадцать оказались обрывками под
 * подписью «непрочитанное». Механизм честности отработал безупречно, а человек открыл не таблицу.
 */
class TableFitnessTest {

    private fun block(role: BlockRole, rows: List<List<String>>) = DocumentBlock(
        role = role,
        label = "",
        text = "",
        grid = GroundedTable(rows),
        headerRows = if (role == BlockRole.TABLE) 1 else 0,
        ids = emptySet(),
        flagged = false,
    )

    private fun layout(vararg blocks: DocumentBlock) =
        DocumentLayout(blocks.toList(), scope = null, uncovered = emptyList(), coverage = null)

    /** Страница, а не бирка: 12 строк по 5 ячеек — 60 ячеек и 72 слова. */
    private val table = block(
        BlockRole.TABLE,
        listOf(listOf("№", "звання", "прізвище та", "дата", "команда")) +
            (1..11).map { listOf("$it.", "солдат", "МУХА Роман", "27.07.2026", "А099$it") },
    )

    private val gridCells = 60

    @Test
    fun `прочитанная страница отдаётся как есть`() {
        assertNull(unfitTable(layout(table), disputed = 4, gridCells = gridCells))
    }

    @Test
    fun `лист из одного непрочитанного — отказ, а не файл`() {
        val only = layout(block(BlockRole.UNREAD, listOf(listOf("Вийськове"), listOf("горович"))))

        val reason = unfitTable(only, disputed = 0, gridCells = 0)

        assertNotNull(reason)
        assertTrue(reason!!.startsWith("Прочитать таблицу не удалось"))
    }

    @Test
    fun `непрочитанного больше трети — отказ`() {
        val dump = (1..40).map { listOf("обрывок$it") }

        val reason = unfitTable(layout(table, block(BlockRole.UNREAD, dump)), 0, gridCells)

        assertNotNull(reason)
        assertTrue(reason!!.contains("трети"))
    }

    @Test
    fun `непрочитанного мало — файл остаётся результатом`() {
        val tail = (1..5).map { listOf("обрывок$it") }

        assertNull(unfitTable(layout(table, block(BlockRole.UNREAD, tail)), 0, gridCells))
    }

    /**
     * Пол доли: на коротком документе «непрочитанное» — не приговор странице. Иначе бирка из шести
     * слов с одним неприсвоенным получала бы отказ за свою длину.
     */
    @Test
    fun `короткий документ по доле не судится`() {
        val small = block(BlockRole.TABLE, listOf(listOf("товар", "ціна"), listOf("гречка", "42")))
        val tail = listOf(listOf("шт"), listOf("грн"), listOf("акт"))

        assertNull(unfitTable(layout(small, block(BlockRole.UNREAD, tail)), 0, gridCells = 4))
    }

    /** Та же треть, которой метрика корпуса объявляет таблицу проваленной, — расходиться нельзя. */
    @Test
    fun `спор на трети ячеек — отказ, а не задание перепроверить лист`() {
        val reason = unfitTable(layout(table), disputed = 20, gridCells = gridCells)

        assertNotNull(reason)
        assertTrue(reason!!.contains("разошлись"))
        assertEquals(WARNING_WALL_SHARE, UNFIT_UNREAD_SHARE, 1e-9)
    }

    @Test
    fun `спор на четверти ячеек файл не отменяет`() {
        assertNull(unfitTable(layout(table), disputed = 15, gridCells = gridCells))
    }

    @Test
    fun `на маленькой таблице спорная ячейка — находка сторожа, а не стена`() {
        val small = block(BlockRole.TABLE, listOf(listOf("товар", "ціна"), listOf("гречка", "42")))

        assertNull(unfitTable(layout(small), disputed = 2, gridCells = 4))
    }

    // --- шапка после свода чтений ---

    private val lead = listOf(
        listOf("№", "звання"),
        listOf("1.", "солдат"),
    )

    @Test
    fun `шапка осталась первой строкой свода — красим её`() {
        val consensus = listOf(listOf("№", "звання"), listOf("1.", "солдат"), listOf("2.", "солдат"))

        assertEquals(1, survivedHeaderRows(lead, consensus, 1))
    }

    /** Находка второго чтения законно встаёт выше шапки — красить её заголовком было бы ложью. */
    @Test
    fun `свод поднял строку данных выше шапки — шапки нет`() {
        val consensus = listOf(listOf("0.", "солдат"), listOf("№", "звання"), listOf("1.", "солдат"))

        assertEquals(0, survivedHeaderRows(lead, consensus, 1))
    }

    /** Пометка спорности шапкой её не отменяет: свёртка чтений ⚠ не различает. */
    @Test
    fun `помеченная шапка остаётся шапкой`() {
        val consensus = listOf(listOf("№⚠", "звання"), listOf("1.", "солдат"))

        assertEquals(1, survivedHeaderRows(lead, consensus, 1))
    }

    @Test
    fun `шапки не было — её и не появляется`() {
        assertEquals(0, survivedHeaderRows(lead, lead, 0))
    }
}
