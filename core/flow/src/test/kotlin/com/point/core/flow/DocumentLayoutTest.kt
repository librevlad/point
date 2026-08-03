package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Документ приходит блоками (#266): шапка, реквизит, сетка и примечание — те же адресованные
 * области, что и ячейка, разрешаемые тем же резолвером.
 *
 * Страница здесь — счёт: заголовок, один реквизит, сетка из двух строк и примечание внизу.
 * Ровно та форма, на которой сегодня в файл уезжает только сетка.
 */
class DocumentLayoutTest {

    private fun atom(id: String, text: String, l: Float, t: Float, r: Float, b: Float) =
        Atom(id, text, Box(l, t, r, b))

    private val page = AtomLayer(
        listOf(
            atom("t1", "Рахунок", 10f, 10f, 100f, 30f),
            atom("t2", "№7", 105f, 10f, 140f, 30f),
            atom("f1", "Клієнт", 10f, 50f, 80f, 70f),
            atom("f2", "Термінал", 85f, 50f, 180f, 70f),
            atom("c1", "Товар", 10f, 100f, 90f, 120f),
            atom("c2", "Кіль-ть", 100f, 100f, 175f, 120f),
            atom("c3", "Гречка", 10f, 140f, 90f, 160f),
            atom("c4", "2", 100f, 140f, 120f, 160f),
            atom("n1", "Відпуск", 10f, 220f, 80f, 240f),
            atom("n2", "заборонено", 85f, 220f, 190f, 240f),
        ),
    )

    private fun ids(vararg id: String, text: String? = null) = CellAnswer.Ids(id.toList(), text)

    private fun text(role: BlockRole, cell: CellAnswer, label: CellAnswer? = null) =
        BlockAnswer(role, label, BlockContent.Text(cell))

    private fun grid(headerRows: Int = 1, vararg rows: List<CellAnswer>) =
        BlockAnswer(BlockRole.TABLE, null, BlockContent.Grid(rows.toList(), headerRows))

    /** Полный честный ответ: каждое слово страницы куда-нибудь отнесено. */
    private fun wholePage() = LayoutAnswer(
        listOf(
            text(BlockRole.TITLE, ids("t1", "t2")),
            text(BlockRole.FIELD, ids("f2"), label = ids("f1")),
            grid(1, listOf(ids("c1"), ids("c2")), listOf(ids("c3"), ids("c4"))),
            text(BlockRole.NOTE, ids("n1", "n2")),
        ),
        scope = DocScope.FULL,
    )

    @Test
    fun `шапка, реквизит и примечание собираются из слов страницы, а не пересказом`() {
        val layout = page.resolveLayout(wholePage())

        assertEquals("Рахунок №7", layout.blocks[0].text)
        assertEquals("Клієнт", layout.blocks[1].label)
        assertEquals("Термінал", layout.blocks[1].text)
        assertEquals(listOf(listOf("Товар", "Кіль-ть"), listOf("Гречка", "2")), layout.blocks[2].grid!!.rows)
        assertEquals("Відпуск заборонено", layout.blocks[3].text)
        assertEquals(DocScope.FULL, layout.scope)
    }

    @Test
    fun `страница присвоена целиком — непокрытого нет, покрытие полное`() {
        val layout = page.resolveLayout(wholePage())

        assertTrue(layout.uncovered.isEmpty())
        assertEquals(1f, layout.coverage!!, 0.001f)
        assertEquals(0, layout.unreadWords)
    }

    /**
     * Дословная приёмка среза: ничего видимого на странице не ушло в файл молча. Слова, о которых
     * ответ не сказал ни слова, едут отдельной частью — их видно, а не «их не было».
     */
    @Test
    fun `непокрытые слова становятся отдельной частью документа, а не тишиной`() {
        val onlyTable = LayoutAnswer(
            listOf(grid(1, listOf(ids("c1"), ids("c2")), listOf(ids("c3"), ids("c4")))),
        )

        val layout = page.resolveLayout(onlyTable)

        assertEquals(
            listOf("Рахунок", "№7", "Клієнт", "Термінал", "Відпуск", "заборонено"),
            layout.uncovered.map { it.text },
        )
        val unread = layout.blocks.last()
        assertEquals(BlockRole.UNREAD, unread.role)
        // По строке страницы на строку файла — порядок чтения, а не порядок выдачи движка.
        assertEquals(
            listOf(listOf("Рахунок №7"), listOf("Клієнт Термінал"), listOf("Відпуск заборонено")),
            unread.grid!!.rows,
        )
        assertEquals(6, layout.unreadWords)
        assertEquals(4f / 10f, layout.coverage!!, 0.001f)
    }

    /**
     * Модель назвала часть документа метками, которых на странице нет: значения не собралось, и
     * молчать об этом нельзя — иначе разрыв связи неотличим от пустого места.
     */
    @Test
    fun `галлюцинированный блок помечается, а не проходит тишиной`() {
        val layout = page.resolveLayout(
            LayoutAnswer(listOf(text(BlockRole.TITLE, ids("w98", "w99")), text(BlockRole.NOTE, ids("n1", "n2")))),
        )

        assertTrue(layout.blocks[0].flagged)
        assertTrue(layout.blocks[0].text.contains('⚠'))
        assertTrue(layout.blocks[0].ids.isEmpty())
        assertFalse("честно собранный блок пометки не получает", layout.blocks[1].flagged)
    }

    /** Своё чтение модели не выбрасывается — но и за прочитанное со страницы не выдаётся. */
    @Test
    fun `блок из чужих меток со своим чтением остаётся предположением`() {
        val layout = page.resolveLayout(
            LayoutAnswer(listOf(text(BlockRole.TITLE, ids("w98", text = "Рахунок №7")))),
        )

        assertEquals("Рахунок №7⚠", layout.blocks[0].text)
    }

    /**
     * Хром существует не как корзина для мусора: без явной области «не документ» полное покрытие
     * страницы было бы недостижимо честным путём. Он присвоен — то есть не потерян, — но
     * содержанием не считается.
     */
    @Test
    fun `хром присвоен, но содержанием не считается`() {
        val layout = page.resolveLayout(
            LayoutAnswer(
                listOf(
                    text(BlockRole.CHROME, ids("t1", "t2")),
                    text(BlockRole.FIELD, ids("f2"), label = ids("f1")),
                    grid(1, listOf(ids("c1"), ids("c2")), listOf(ids("c3"), ids("c4"))),
                    text(BlockRole.NOTE, ids("n1", "n2")),
                ),
            ),
        )

        assertTrue("присвоенное не теряется", layout.uncovered.isEmpty())
        assertEquals("два слова хрома в содержание не идут", 8f / 10f, layout.coverage!!, 0.001f)
    }

    /**
     * Ответ по старому контракту (дословные ячейки, ни одной метки) покрытия НЕ измеряет: мы не
     * знаем, что он покрыл, и ноль здесь был бы такой же выдумкой, как единица. Сегодняшний ответ
     * от появления блоков не становится хуже — он просто остаётся неизмеренным.
     */
    @Test
    fun `дословный ответ покрытия не измеряет и лишних частей не рождает`() {
        val literal = LayoutAnswer(
            listOf(
                grid(
                    1,
                    listOf(CellAnswer.Literal("Товар"), CellAnswer.Literal("Кіль-ть")),
                    listOf(CellAnswer.Literal("Гречка"), CellAnswer.Literal("2")),
                ),
            ),
        )

        val layout = page.resolveLayout(literal)

        assertNull(layout.coverage)
        assertTrue(layout.uncovered.isEmpty())
        assertEquals(1, layout.blocks.size)
    }

    /** Рукопись, PDF и текст: структура читается, заземлять нечем — и мы этого не скрываем. */
    @Test
    fun `без слоя структура читается, а покрытие не выдумывается`() {
        val layout = literalLayout(
            LayoutAnswer(
                listOf(
                    text(BlockRole.TITLE, CellAnswer.Literal("Відомість")),
                    grid(0, listOf(CellAnswer.Literal("Гречка"), CellAnswer.Literal("2"))),
                ),
            ),
        )

        assertEquals("Відомість", layout.blocks[0].text)
        assertEquals(listOf(listOf("Гречка", "2")), layout.blocks[1].grid!!.rows)
        assertNull(layout.coverage)
        assertTrue(layout.uncovered.isEmpty())
    }

    /** Метка без слоя ничего не адресует — чтение остаётся, но подтверждать его некому. */
    @Test
    fun `без слоя метка не выдаётся за прочитанное со страницы`() {
        val layout = literalLayout(LayoutAnswer(listOf(text(BlockRole.TITLE, ids("t1", text = "Рахунок")))))

        assertEquals("Рахунок⚠", layout.blocks[0].text)
    }

    /** Заземлённость ячейки — это про происхождение символов, и адрес блока её не размывает. */
    @Test
    fun `заземлённые ячейки сетки видны в координатах своего блока`() {
        val layout = page.resolveLayout(wholePage())

        assertEquals(setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), layout.blocks[2].grid!!.structural)
    }

    @Test
    fun `вопрос о шапке решается числом, и «шапки нет» — тоже ответ`() {
        val noHeader = page.resolveLayout(
            LayoutAnswer(listOf(grid(0, listOf(ids("c3"), ids("c4"))))),
        )
        val twoLevel = page.resolveLayout(
            LayoutAnswer(listOf(grid(2, listOf(ids("c1"), ids("c2")), listOf(ids("c3"), ids("c4"))))),
        )

        assertEquals(0, noHeader.gridHeaderRows)
        assertEquals("нет", headerLabel(noHeader.gridHeaderRows))
        assertEquals(2, twoLevel.gridHeaderRows)
        assertEquals("2", headerLabel(twoLevel.gridHeaderRows))
    }

    /** Голосование чтений живёт этажом выше и возвращает сетку туда, где её нашли. */
    @Test
    fun `сведённая сетка встаёт на своё место в документе, а не отдельным файлом`() {
        val layout = page.resolveLayout(wholePage())

        val voted = layout.withGrid(GroundedTable(listOf(listOf("Товар", "Кіль-ть"), listOf("Гречка", "3⚠"))))

        assertEquals(BlockRole.TABLE, voted.blocks[2].role)
        assertEquals("3⚠", voted.blocks[2].grid!!.rows[1][1])
        assertEquals("документ вокруг сетки не тронут", "Відпуск заборонено", voted.blocks[3].text)
    }

    /** Сетки в ведущем чтении не было, а голосование её дало — потерять строки хуже всего. */
    @Test
    fun `сведённая сетка не пропадает, когда в документе сетки не было`() {
        val layout = page.resolveLayout(
            LayoutAnswer(listOf(text(BlockRole.TITLE, ids("t1", "t2")), text(BlockRole.NOTE, ids("n1", "n2")))),
        )

        val voted = layout.withGrid(GroundedTable(listOf(listOf("Гречка", "2"))))

        val tableAt = voted.blocks.indexOfFirst { it.role == BlockRole.TABLE }
        val unreadAt = voted.blocks.indexOfFirst { it.role == BlockRole.UNREAD }
        assertEquals(listOf(listOf("Гречка", "2")), voted.blocks[tableAt].grid!!.rows)
        assertTrue("сетка не уезжает в хвост непрочитанного", tableAt in 0 until unreadAt)
    }
}
