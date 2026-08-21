package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Несколько страниц набора — одна таблица (#1207): строки складываются в порядке страниц,
 * заголовки и спорные ячейки помнят свои новые номера строк, повторённая шапка не двоится.
 */
class StitchSheetsTest {

    private val first = SheetPlan(
        rows = listOf(listOf("Товар", "Кол-во"), listOf("Гречка", "2"), listOf("Рис", "1")),
        headerRows = setOf(0),
        candidates = mapOf((1 to 1) to listOf("2", "7")),
    )

    private val second = SheetPlan(
        rows = listOf(listOf("Товар", "Кол-во"), listOf("Соль", "3")),
        headerRows = setOf(0),
        candidates = mapOf((1 to 1) to listOf("3", "8")),
    )

    @Test
    fun `страницы идут одна за другой в заданном порядке`() {
        val plan = stitchSheets(listOf(first, second))

        assertEquals(
            listOf(listOf("Товар", "Кол-во"), listOf("Гречка", "2"), listOf("Рис", "1"), listOf("Соль", "3")),
            plan.rows,
        )
        assertEquals(
            listOf(listOf("Товар", "Кол-во"), listOf("Соль", "3"), listOf("Гречка", "2"), listOf("Рис", "1")),
            stitchSheets(listOf(second, first)).rows,
        )
    }

    @Test
    fun `шапка, повторённая следующей страницей слово в слово, второй раз не кладётся`() {
        val plan = stitchSheets(listOf(first, second))

        assertEquals(setOf(0), plan.headerRows)
        assertEquals(1, plan.rows.count { it == listOf("Товар", "Кол-во") })
    }

    @Test
    fun `другая шапка на следующей странице остаётся шапкой — там другая таблица`() {
        val other = SheetPlan(
            rows = listOf(listOf("Услуга", "Сумма"), listOf("Доставка", "50")),
            headerRows = setOf(0),
        )

        val plan = stitchSheets(listOf(first, other))

        assertEquals(setOf(0, 3), plan.headerRows)
        assertEquals(listOf("Услуга", "Сумма"), plan.rows[3])
    }

    @Test
    fun `спорные ячейки второй страницы помнят свой новый номер строки`() {
        val plan = stitchSheets(listOf(first, second))

        assertEquals(listOf("2", "7"), plan.candidates[1 to 1])
        assertEquals(listOf("3", "8"), plan.candidates[3 to 1])
        assertEquals(2, plan.candidates.size)
    }

    @Test
    fun `одна страница остаётся собой`() {
        assertEquals(first, stitchSheets(listOf(first)))
    }
}
