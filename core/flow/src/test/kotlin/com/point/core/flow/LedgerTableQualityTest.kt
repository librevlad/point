package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerTableQualityTest {

    private val ledger: List<List<Pair<String, String>>> = listOf(
        listOf(
            "№" to "№",
            "n/n" to "п/п",
            "Вийськова" to "Військове",
            "звання" to "звання",
            "Празвище, 1м'я та по батьков" to "Прізвище, ім'я та по батькові",
            "Дата прибуття А5003" to "Дата прибуття",
        ),
        listOf(
            "1." to "1.",
            "солдат" to "солдат",
            "МУХА Роман" to "МУХА Роман",
            "27.07.2026" to "27.07.2026",
            "A0998" to "А0998",
        ),
        listOf(
            "4." to "4.",
            "солдат" to "солдат",
            "УСАТИЙ Олександр Володимиров" to "УСАТИЙ Олександр Володимирович",
            "31.07.2026" to "31.07.2026",
            "A2641" to "А2641",
        ),
        listOf(
            "[6" to "6.",
            "солдат" to "солдат",
            "ОКАЛЬЧУК Виктор Михайлович" to "КОВАЛЬЧУК Віктор Михайлович",
            "АА 31.07 20261" to "31.07.2026",
            "А4447" to "А4447",
        ),
        listOf(
            "7," to "7.",
            "солдат" to "солдат",
            "КРУПА Володин ПОНИ" to "КРУПА Володимир Петрович",
            "31.07.2026" to "31.07.2026",
            "A2573" to "А2573",
        ),
        listOf(
            "_8." to "8.",
            "солдат'" to "солдат",
            "БЕЛЯЕВ Олександр Олександров" to "БЄЛЯЄВ Олександр Олександрович",
            "31.07.2026" to "31.07.2026",
            "|1А7095, А4718" to "А7095, А4718",
        ),
        listOf(
            "9." to "9.",
            "солдат '" to "солдат",
            "ДУТЧАК Андри Васильович!" to "ДУТЧАК Андрій Васильович",
            "(31.07.2026" to "31.07.2026",
            "А4152_" to "А4152",
        ),
    )

    private val layer = AtomLayer(
        ledger.flatMapIndexed { r, row ->
            row.mapIndexed { c, (engine, _) ->
                Atom(
                    id = "w${r}_$c",
                    text = engine,
                    box = Box(200f * c, 100f * r, 200f * c + 180f, 100f * r + 20f),
                )
            }
        },
    )

    private fun answer(withReading: Boolean): List<List<CellAnswer>> =
        ledger.mapIndexed { r, row ->
            row.mapIndexed { c, (_, model) ->
                CellAnswer.Ids(listOf("w${r}_$c"), model.takeIf { withReading })
            }
        }

    private fun bare(cell: String) = cell.replace("⚠", "")

    private val differing = ledger.sumOf { row -> row.count { (engine, model) -> engine != model } }

    private fun engineWins(withReading: Boolean): Int {
        val table = layer.resolveCells(answer(withReading))
        return ledger.withIndex().sumOf { (r, row) ->
            row.withIndex().count { (c, pair) ->
                val (engine, model) = pair
                engine != model && bare(table.rows[r][c]) == engine
            }
        }
    }

    @Test
    fun `сегодня почерк движка выигрывает каждую разошедшуюся ячейку`() {
        assertEquals("ячеек, где движок и модель разошлись", 21, differing)
        assertEquals("движок выигрывает все, пока модель молчит", 21, engineWins(withReading = false))
    }

    @Test
    fun `со своим чтением модели движок держит только цифру`() {
        assertEquals("движок остаётся лишь там, где спор о цифрах", 3, engineWins(withReading = true))
    }

    @Test
    fun `каждая ячейка, оставшаяся за движком, помечена и несёт оба чтения`() {
        val table = layer.resolveCells(answer(withReading = true))
        ledger.forEachIndexed { r, row ->
            row.forEachIndexed { c, (engine, model) ->
                if (engine == model || bare(table.rows[r][c]) != engine) return@forEachIndexed
                assertTrue(
                    "«$engine» осталась за движком — она обязана быть помечена",
                    table.rows[r][c].contains('⚠'),
                )
                assertEquals(
                    "рядом с «$engine» обязано стоять чтение модели",
                    listOf(engine, model),
                    table.candidates[r to c],
                )
            }
        }
    }

    @Test
    fun `цифру движка модель не переписывает даже чтением`() {
        val table = layer.resolveCells(answer(withReading = true))

        assertEquals("Дата прибуття А5003⚠", table.rows[0][5])
        assertEquals("АА 31.07 20261⚠", table.rows[3][3])
        assertEquals("|1А7095, А4718⚠", table.rows[5][4])
    }

    @Test
    fun `шум движка вокруг слова снимается без единой пометки`() {
        val table = layer.resolveCells(answer(withReading = true))

        assertEquals("6.", table.rows[3][0])
        assertEquals("7.", table.rows[4][0])
        assertEquals("8.", table.rows[5][0])
        assertEquals("солдат", table.rows[5][1])
        assertEquals("31.07.2026", table.rows[6][3])
        assertEquals("А4152", table.rows[6][4])
    }

    @Test
    fun `номер команды приезжает кириллицей и без пометки`() {
        val table = layer.resolveCells(answer(withReading = true))

        assertEquals("А0998", table.rows[1][4])
        assertEquals("А2641", table.rows[2][4])
        assertEquals("А2573", table.rows[4][4])
    }

    @Test
    fun `пометок меньше трети — лист остаётся таблицей`() {
        val table = layer.resolveCells(answer(withReading = true))
        val cells = table.rows.sumOf { row -> row.count { it.isNotBlank() } }
        val flagged = table.rows.sumOf { row -> row.count { it.contains('⚠') } }

        assertEquals(36, cells)
        assertTrue("помечено $flagged из $cells", flagged * 3 < cells)
    }
}
