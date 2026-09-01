package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TableMetricTest {

    private fun ledger(
        rows: Int,
        columns: Int = 3,
        header: Boolean = true,
        named: List<ExpectedRow>,
    ) = TableExpectation("23", rows, columns, keyColumn = 0, header = header, namedRows = named)

    private fun row(key: String, vararg cells: Pair<Int, String>) = ExpectedRow(key, cells.toMap())

    private val head = listOf("Артикул", "Наименование", "Кол-во")

    @Test
    fun `совпавшее значение совпало, а расхождение под меткой — цена проверки, не провал`() {
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 1 to "Гречка", 2 to "120"), row("11006", 2 to "40"))),
            listOf(
                head,
                listOf("11004", "Гречка", "12О⚠"),
                listOf("11006", "Рис", "40"),
            ),
        )

        assertEquals(2, score.matchedCells)
        assertEquals(1, score.flagged.size)
        assertTrue("предупреждённое расхождение молчаливым не бывает", score.silent.isEmpty())
        assertTrue("человек предупреждён — значит действие выполнено", score.passed)
    }

    @Test
    fun `расхождение без метки — главное число и провал`() {
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 1 to "Гречка", 2 to "120"), row("11006", 2 to "40"))),
            listOf(
                head,
                listOf("11004", "Гречка", "125"),
                listOf("11006", "Рис", "40"),
            ),
        )

        assertEquals(1, score.silent.size)
        assertEquals(CellDiff("11004", 2, "120", "125"), score.silent.single())
        assertEquals(listOf(TableFailure.SILENT_CELLS), score.failures)
    }

    @Test
    fun `потерянная строка названа поимённо, а её значения считаются молчаливыми`() {
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 1 to "Рис", 2 to "40"))),
            listOf(head, listOf("11004", "Гречка", "120")),
        )

        assertEquals(listOf("11004"), score.found)
        assertEquals(listOf("11006"), score.lost)
        assertEquals(2, score.silent.size)
        assertTrue("ячейки потерянной строки — не пустые, а отсутствующие", score.silent.all { it.actual == null })
        assertEquals(listOf(TableFailure.SILENT_CELLS, TableFailure.LOST_ROWS), score.failures)
    }

    @Test
    fun `строки узнаются по артикулу, даже когда колонка съехала`() {

        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 1 to "Гречка", 2 to "120"), row("11006", 1 to "Рис"))),
            listOf(
                listOf("", "Артикул", "Наименование", "Кол-во"),
                listOf("", "11004", "Гречка", "120"),
                listOf("", "11006", "Рис", "40"),
            ),
        )

        assertEquals(3, score.matchedCells)
        assertTrue(score.silent.isEmpty() && score.lost.isEmpty())

        assertEquals(listOf(TableFailure.WRONG_SHAPE), score.failures)
    }

    @Test
    fun `испорченный ключ — строка и потеряна, и лишняя`() {
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40"))),
            listOf(head, listOf("11004", "Гречка", "120"), listOf("11О06", "Рис", "40")),
        )

        assertEquals(listOf("11006"), score.lost)
        assertEquals(1, score.extra)
        assertTrue(TableFailure.EXTRA_ROWS in score.failures)
    }

    @Test
    fun `точка и скобка, прилипшие к ключу от вёрстки окна, — оформление, а не другой номер`() {

        val e = parseTableExpectation(
            "06",
            listOf(
                "строк 4",
                "колонок 2",
                "ключ 1",
                "шапка да",
                "--",
                "286/2/18/138\t18.10.18",
                "286/2/18/137\t18.10.18",
                "286/2/20/3\t13.02.20",
                "286/2/18/128\t11.10.18",
            ).joinToString("\n"),
        )

        val score = scoreTable(
            e,
            listOf(
                listOf("Номер", "Дата"),
                listOf(".286/2/18/138", "18.10.18"),
                listOf("(286/2/18/137", "18.10.18"),
                listOf(".286/2/20/3.", "13.02.20"),
                listOf(".286/2/18/138⚠", "11.10.18"),
            ),
        )

        assertEquals(listOf("286/2/18/138", "286/2/18/137", "286/2/20/3"), score.found)
        assertEquals(3, score.matchedCells)

        assertEquals(listOf("286/2/18/128"), score.lost)
        assertEquals(1, score.extra)
        assertEquals(listOf(CellDiff("286/2/18/128", 1, "11.10.18", null)), score.silent)
    }

    @Test
    fun `лишними считаются строки сверх неназванных, а не сверх названных`() {

        val expectation = ledger(4, named = listOf(row("11004"), row("11006")))
        val body = listOf(listOf("11004", "Гречка", "120"), listOf("11006", "Рис", "40"))
        val unnamed = listOf(listOf("", "Соль", "10"), listOf("", "Сахар", "5"))

        assertEquals(0, scoreTable(expectation, listOf(head) + body + unnamed).extra)
        assertEquals(1, scoreTable(expectation, listOf(head) + body + unnamed + listOf(listOf("", "?", "?"))).extra)
    }

    @Test
    fun `подмена строк ловится, хотя длина файла сошлась`() {
        val expectation = ledger(3, named = listOf(row("11004"), row("11006"), row("11012")))
        val score = scoreTable(
            expectation,
            listOf(head, listOf("11004", "Гречка", "120"), listOf("12345", "?", "?"), listOf("54321", "?", "?")),
        )

        assertEquals(3, score.tableRows)
        assertEquals(listOf("11006", "11012"), score.lost)
        assertEquals(2, score.extra)
    }

    @Test
    fun `стена предупреждений — не честность, а невыполненное действие`() {

        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40"))),
            listOf(head, listOf("11004⚠", "Гречка⚠", "120⚠"), listOf("11006⚠", "Рис⚠", "40⚠")),
        )

        assertEquals(2, score.matchedCells)
        assertTrue(score.silent.isEmpty())
        assertEquals(1.0, score.markedShare!!, 0.001)
        assertEquals(listOf(TableFailure.WARNING_WALL), score.failures)
    }

    @Test
    fun `документ вокруг сетки не разбавляет долю пометок и не выдумывает лишних строк`() {
        val expectation = ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40")))
        val grid = listOf(head, listOf("11004⚠", "Гречка⚠", "120⚠"), listOf("11006⚠", "Рис⚠", "40⚠"))

        val bare = scoreTable(expectation, grid)
        val document = scoreTable(
            expectation,
            listOf(listOf("Накладная №7")) +
                listOf(listOf("Клиент", "Терминал Пр. 117")) +
                grid +
                listOf(listOf("Кладовщик не имеет права отпускать товар")) +
                listOf(listOf("‚ Be:"), listOf("MPs"), listOf("си")),
        )

        assertEquals("строк таблицы столько же", bare.tableRows, document.tableRows)
        assertEquals("выдуманных строк не прибавилось", bare.extra, document.extra)
        assertEquals("доля пометок не разбавлена", bare.markedShare!!, document.markedShare!!, 0.001)
        assertEquals("и стена по-прежнему стоит", bare.failures, document.failures)
        assertEquals(listOf(TableFailure.WARNING_WALL), document.failures)
    }

    @Test
    fun `хвост непрочитанного в счёт таблицы не идёт`() {
        // Служебной строки-маркера в листе больше нет (#1368): узкие строки хвоста
        // отсеивает мерка ширины — как и остальной документ вокруг сетки.
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40"))),
            listOf(head, listOf("11004", "Гречка", "120"), listOf("11006", "Рис", "40")) +
                listOf(listOf("Кладовщик не имеет права"), listOf("підпис печатка")),
        )

        assertEquals(2, score.tableRows)
        assertEquals(0, score.extra)
        assertTrue("таблица сдана — хвост её не топит", score.passed)
    }

    @Test
    fun `артикул под меткой ⚠ всё равно узнаёт свою строку`() {

        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40"))),
            listOf(head, listOf("11004⚠", "Гречка", "120"), listOf("11006⚠", "Рис", "40")),
        )

        assertEquals(listOf("11004", "11006"), score.found)
        assertEquals(2, score.matchedCells)
    }

    @Test
    fun `формат за расхождение не считается, а другое число — считается`() {
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "1 200"), row("11006", 2 to "1,5"))),
            listOf(head, listOf("11004", "Гречка", "1200"), listOf("11006", "Рис", "15")),
        )

        assertEquals("разрядный пробел значения не меняет", 1, score.matchedCells)
        assertEquals("«1,5» и «15» — разные числа", 1, score.silent.size)
    }

    @Test
    fun `шапку эталона не отдаём первой строке вслепую`() {

        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40"))),
            listOf(listOf("11004", "Гречка", "120"), listOf("11006", "Рис", "40")),
        )

        assertEquals(2, score.tableRows)
        assertEquals(listOf("11004", "11006"), score.found)
        assertTrue(score.passed)
    }

    @Test
    fun `таблицы нет вовсе — всё потеряно и всё молчит`() {
        val score = scoreTable(ledger(2, named = listOf(row("11004", 2 to "120"))), emptyList())

        assertEquals(0, score.tableRows)
        assertEquals(listOf("11004"), score.lost)
        assertEquals(1, score.silent.size)
        assertNull("помечать нечего — доли нет", score.markedShare)
        assertEquals(0.0, score.cellShare!!, 0.001)
    }

    @Test
    fun `эталон без сверенных значений не даёт «сдано» — судить было нечем`() {

        val score = scoreTable(
            ledger(2, named = listOf(row("11004"), row("11006"))),
            listOf(head, listOf("11004", "", ""), listOf("11006", "", "")),
        )

        assertEquals(0, score.checkedCells)
        assertTrue("причин провала действительно нет", score.failures.isEmpty())
        assertFalse("сверять было нечего — значит не сдано", score.passed)
        assertTrue(score.unjudged)
    }

    @Test
    fun `отчёт не говорит «сдано» там, где сверять было нечего`() {

        val text = renderTableScore(
            scoreTable(
                ledger(2, named = listOf(row("11004"), row("11006"))),
                listOf(head, listOf("11004", "", ""), listOf("11006", "", "")),
            ),
        )

        assertTrue(text, text.contains("проверить нечем"))
        assertFalse(text, text.contains("**всё верно**"))
    }

    @Test
    fun `одно сверенное значение уже даёт право на «сдано»`() {
        val text = renderTableScore(
            scoreTable(
                ledger(2, named = listOf(row("11004", 2 to "120"), row("11006"))),
                listOf(head, listOf("11004", "Гречка", "120"), listOf("11006", "Рис", "40")),
            ),
        )

        assertTrue(text, text.contains("**всё верно**"))
    }

    @Test
    fun `эталон читается — числа документа, ключ и сверенные ячейки`() {
        val text = listOf(
            "# ведомость владельца",
            "строк 35   # без строки шапки",
            "колонок 8",
            "ключ 1",
            "шапка да",
            "--",
            "11004\tГречка\t\t120",
            "11006",
        ).joinToString("\n")

        val expectation = parseTableExpectation("23", text)

        assertEquals(35, expectation.documentRows)
        assertEquals(8, expectation.documentColumns)
        assertEquals(0, expectation.keyColumn)
        assertTrue(expectation.header)
        assertEquals(listOf("11004", "11006"), expectation.namedRows.map { it.key })
        assertEquals(mapOf(1 to "Гречка", 3 to "120"), expectation.namedRows.first().cells)
        assertEquals("названо не значит сверено", emptyMap<Int, String>(), expectation.namedRows.last().cells)
    }

    @Test
    fun `без числа строк эталон не читается — иначе потерянные строки исчезнут`() {

        val e = assertThrows(IllegalArgumentException::class.java) {
            parseTableExpectation("23", "колонок 8\n--\n11004")
        }
        assertTrue(e.message!!.contains("строк"))
    }

    @Test
    fun `строка эталона без ключа и повторный ключ — ошибка, а не тихий пропуск`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTableExpectation("23", "строк 3\nколонок 3\n--\n\t\tГречка")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseTableExpectation("23", "строк 3\nколонок 3\n--\n11004\n11004")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseTableExpectation("23", "строк 1\nколонок 3\nвесна да\n--\n11004")
        }
    }

    @Test
    fun `названных строк не может быть больше, чем строк в документе`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTableExpectation("23", "строк 1\nколонок 3\n--\n11004\n11006")
        }
    }

    @Test
    fun `стиль кавычки не расхождение, а слово внутри неё — расхождение`() {
        val e = parseTableExpectation(
            "к",
            "строк 2\nколонок 2\nключ 1\n--\n11401\tПластівці вівсяні “Екстра”\n",
        )

        val same = scoreTable(e, listOf(listOf("11401", "Пластівці вівсяні \"Екстра\"")))
        assertEquals(1, same.matchedCells)
        assertEquals(0, same.silent.size)

        val other = scoreTable(e, listOf(listOf("11401", "Пластівці вівсяні \"Преміум\"")))
        assertEquals(0, other.matchedCells)
        assertEquals(1, other.silent.size)
    }

    @Test
    fun `заготовка эталона кадра 23 читается — её правит человек руками`() {

        val file = File(repo, "tools/corpus/23.expected.tsv")
        assertTrue("не найден ${file.absolutePath}", file.exists())

        val expectation = parseTableExpectation("23", file.readText())

        assertEquals(35, expectation.documentRows)

        assertEquals(9, expectation.documentColumns)
        assertEquals(0, expectation.keyColumn)
        assertEquals("артикулы кадра 23", 27, expectation.namedRows.size)
        assertTrue("11004" in expectation.namedRows.map { it.key })

        assertEquals("строк со сверенными значениями", 26, expectation.namedRows.count { it.cells.isNotEmpty() })

        assertEquals("сверенных значений", 49, expectation.namedRows.sumOf { it.cells.size })
    }

    @Test
    fun `все эталоны каталога разбираются и называют хотя бы одно сверенное значение`() {
        val dir = File(repo, "tools/corpus")
        val files = dir.listFiles { f: File -> f.name.endsWith(EXPECTED_SUFFIX) }.orEmpty().sortedBy { it.name }
        assertTrue("нет эталонов в ${dir.absolutePath}", files.isNotEmpty())

        files.forEach { file ->
            val frame = file.name.removeSuffix(EXPECTED_SUFFIX)
            val expectation = parseTableExpectation(frame, file.readText())

            assertTrue("$frame — «строк» должно быть больше нуля", expectation.documentRows > 0)
            assertTrue("$frame — «колонок» должно быть больше нуля", expectation.documentColumns > 0)
            assertTrue("$frame — не названо ни одной строки", expectation.namedRows.isNotEmpty())

            assertTrue(
                "$frame — ни одного сверенного значения: метрике нечем судить",
                expectation.namedRows.any { it.cells.isNotEmpty() },
            )

            val score = scoreTable(expectation, perfectTable(expectation))
            assertTrue("$frame — эталон не проходит сам себя: ${score.failures}", score.passed)
            assertEquals("$frame — сверенных ячеек", score.checkedCells, score.matchedCells)
        }
    }

    private fun perfectTable(e: TableExpectation): List<List<String>> = buildList {
        if (e.header) add(List(e.documentColumns) { "шапка ${it + 1}" })
        e.namedRows.forEach { named ->
            add(
                List(e.documentColumns) { column ->
                    when (column) {
                        e.keyColumn -> named.key
                        else -> named.cells[column].orEmpty()
                    }
                },
            )
        }
        repeat(e.documentRows - e.namedRows.size) { add(List(e.documentColumns) { "" }) }
    }

    @Test
    fun `эталоны кадров 01, 04, 06 и 18 держат числа, снятые с кадров`() {

        val expected = mapOf(
            "01" to Shape(12, 7, 0, header = true, named = 5, checked = 5, cells = 25),
            "04" to Shape(12, 7, 0, header = true, named = 5, checked = 5, cells = 25),
            "06" to Shape(16, 7, 1, header = true, named = 16, checked = 8, cells = 24),
            "18" to Shape(2, 7, 1, header = false, named = 2, checked = 2, cells = 10),
        )

        expected.forEach { (frame, shape) ->
            val file = File(repo, "tools/corpus/$frame$EXPECTED_SUFFIX")
            assertTrue("не найден ${file.absolutePath}", file.exists())
            val e = parseTableExpectation(frame, file.readText())

            assertEquals("$frame — строк", shape.rows, e.documentRows)
            assertEquals("$frame — колонок", shape.columns, e.documentColumns)
            assertEquals("$frame — колонка-ключ", shape.keyColumn, e.keyColumn)
            assertEquals("$frame — шапка", shape.header, e.header)
            assertEquals("$frame — названо строк", shape.named, e.namedRows.size)
            assertEquals(
                "$frame — строк со сверенными значениями",
                shape.checked,
                e.namedRows.count { it.cells.isNotEmpty() },
            )
            assertEquals("$frame — сверенных значений", shape.cells, e.namedRows.sumOf { it.cells.size })
        }
    }

    private data class Shape(
        val rows: Int,
        val columns: Int,
        val keyColumn: Int,
        val header: Boolean,
        val named: Int,
        val checked: Int,
        val cells: Int,
    )
}

private const val EXPECTED_SUFFIX = ".expected.tsv"
