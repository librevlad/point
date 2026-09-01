package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetPlanTest {

    private fun block(
        role: BlockRole,
        text: String = "",
        label: String = "",
        grid: GroundedTable? = null,
        headerRows: Int = 0,
        ids: Set<String> = emptySet(),
    ) = DocumentBlock(role, label, text, grid, headerRows, ids, flagged = text.contains('⚠'))

    private fun layout(vararg blocks: DocumentBlock, uncovered: List<Atom> = emptyList(), coverage: Float? = null) =
        DocumentLayout(blocks.toList(), scope = null, uncovered = uncovered, coverage = coverage)

    private val table = GroundedTable(listOf(listOf("Товар", "Кіль-ть"), listOf("Гречка", "2")))

    @Test
    fun `блоки ложатся на один лист в порядке документа`() {
        val plan = layoutSheet(
            layout(
                block(BlockRole.TITLE, text = "Рахунок №7"),
                block(BlockRole.FIELD, label = "Клієнт", text = "Термінал"),
                block(BlockRole.TABLE, grid = table, headerRows = 1),
                block(BlockRole.NOTE, text = "Відпуск заборонено"),
            ),
        )

        assertEquals(
            listOf(
                listOf("Рахунок №7"),
                listOf("Клієнт", "Термінал"),
                listOf("Товар", "Кіль-ть"),
                listOf("Гречка", "2"),
                listOf("Відпуск заборонено"),
            ),
            plan.rows,
        )
    }

    @Test
    fun `шапка стоит по факту, а не на первой строке листа`() {
        val withHeader = layoutSheet(
            layout(
                block(BlockRole.TITLE, text = "Рахунок №7"),
                block(BlockRole.TABLE, grid = table, headerRows = 1),
            ),
        )
        val without = layoutSheet(layout(block(BlockRole.TABLE, grid = table, headerRows = 0)))
        val twoLevel = layoutSheet(layout(block(BlockRole.TABLE, grid = table, headerRows = 2)))

        assertEquals("заголовок документа шапкой сетки не становится", setOf(1), withHeader.headerRows)
        assertEquals("шапки нет — жирных строк нет", emptySet<Int>(), without.headerRows)
        assertEquals(setOf(0, 1), twoLevel.headerRows)
    }

    @Test
    fun `хром на лист не едет`() {
        val plan = layoutSheet(
            layout(
                block(BlockRole.CHROME, text = "12:45 Wi-Fi 100%"),
                block(BlockRole.TABLE, grid = table, headerRows = 1),
            ),
        )

        assertFalse(plan.rows.flatten().any { it.contains("Wi-Fi") })
        assertEquals(setOf(0), plan.headerRows)
    }

    @Test
    fun `непрочитанное едет на лист без слов Point — пустая строка и граница в плане`() {
        // #1368: подпись «Непрочитанное — …» уезжала внутрь файла, который человек отдаст
        // дальше. Слова страницы остаются, граница хвоста — знание плана, а не текст.
        val plan = layoutSheet(
            layout(
                block(BlockRole.TABLE, grid = table, headerRows = 1),
                block(BlockRole.UNREAD, grid = GroundedTable(listOf(listOf("Клієнт Термінал")))),
            ),
        )

        assertEquals(listOf(""), plan.rows[2])
        assertEquals(listOf("Клієнт Термінал"), plan.rows[3])
        assertEquals(3, plan.unreadFrom)
        assertFalse(
            "служебных слов Point в листе не бывает",
            plan.rows.flatten().any { it.contains("Непрочитанное") },
        )
    }

    @Test
    fun `непрочитанное вперемешку с документом границей не объявляется`() {
        val plan = layoutSheet(
            layout(
                block(BlockRole.UNREAD, grid = GroundedTable(listOf(listOf("шапка бланка")))),
                block(BlockRole.TABLE, grid = table, headerRows = 1),
            ),
        )

        assertEquals("после хвоста идёт документ — одна граница была бы враньём", null, plan.unreadFrom)
        assertEquals(listOf("шапка бланка"), plan.rows[0])
    }

    @Test
    fun `спорные ячейки сетки переезжают в координаты листа`() {
        val disputed = GroundedTable(table.rows, mapOf((1 to 1) to listOf("2", "3")))

        val plan = layoutSheet(
            layout(
                block(BlockRole.TITLE, text = "Рахунок №7"),
                block(BlockRole.TABLE, grid = disputed, headerRows = 1),
            ),
        )

        assertEquals(listOf("2", "3"), plan.candidates[2 to 1])
        assertTrue("старых координат в плане не осталось", plan.candidates.keys == setOf(2 to 1))
    }

    @Test
    fun `рукопись помечает цифры, а слова оставляет как есть`() {
        val plan = layoutSheet(
            layout(
                block(BlockRole.TITLE, text = "Відомість"),
                block(BlockRole.TABLE, grid = table, headerRows = 1),
            ),
            ReadingMode.HANDWRITTEN,
        )

        assertEquals("Відомість", plan.rows[0].single())
        assertEquals(listOf("Товар", "Кіль-ть"), plan.rows[1])
        assertEquals(listOf("Гречка", "2⚠"), plan.rows[2])
    }

    @Test
    fun `печать цифры не метит — гарантия там другая`() {
        val plan = layoutSheet(layout(block(BlockRole.TABLE, grid = table, headerRows = 1)), ReadingMode.PRINTED)

        assertEquals(listOf("Гречка", "2"), plan.rows[1])
    }

    @Test
    fun `исправление остаётся исправлением, а не превращается в пометку`() {
        val corrections = GroundedTable(listOf(listOf("~~53~~ 40", "0,72⚠")))

        val plan = layoutSheet(layout(block(BlockRole.TABLE, grid = corrections)), ReadingMode.HANDWRITTEN)

        assertEquals(listOf("~~53~~ 40", "0,72⚠"), plan.rows[0])
    }

    @Test
    fun `на печати обещание держится покрытием страницы`() {
        val covered = layout(block(BlockRole.TABLE, grid = table, headerRows = 1), coverage = 1f)
        val lost = layout(
            block(BlockRole.TABLE, grid = table, headerRows = 1),
            uncovered = listOf(Atom("f1", "Клієнт", Box(0f, 0f, 10f, 10f))),
            coverage = 0.5f,
        )

        assertEquals(true, coveredClaim(covered, layoutSheet(covered), ReadingMode.PRINTED))
        assertEquals(false, coveredClaim(lost, layoutSheet(lost), ReadingMode.PRINTED))
    }

    @Test
    fun `на рукописи обещание держится пометкой каждой цифры`() {
        val doc = layout(block(BlockRole.TABLE, grid = table, headerRows = 1))

        val marked = layoutSheet(doc, ReadingMode.HANDWRITTEN)
        val unmarked = layoutSheet(doc, ReadingMode.PRINTED)

        assertEquals(true, coveredClaim(doc, marked, ReadingMode.HANDWRITTEN))
        assertEquals(false, coveredClaim(doc, unmarked, ReadingMode.HANDWRITTEN))
    }

    @Test
    fun `покрытие не измерялось — обещания нет ни в одну сторону`() {
        val doc = layout(block(BlockRole.TABLE, grid = table, headerRows = 1))

        assertNull(coveredClaim(doc, layoutSheet(doc), ReadingMode.PRINTED))
    }

    @Test
    fun `тривиальный план повторяет сегодняшнее поведение`() {
        assertEquals(setOf(0), sheetPlanOf(table.rows).headerRows)
        assertEquals(emptySet<Int>(), sheetPlanOf(emptyList()).headerRows)
    }
}
