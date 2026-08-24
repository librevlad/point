package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TableUsabilityTest {

    private val caption = listOf(UNREAD_CAPTION)

    @Test
    fun `чистая таблица годна целиком`() {
        val score = scoreUsable(
            "чистая",
            listOf(
                listOf("Артикул", "Наименование", "Кол-во"),
                listOf("11004", "Гречка", "120"),
                listOf("11006", "Рис", "40"),
            ),
        )

        assertEquals(9, score.documentCells)
        assertEquals(0, score.dumpCells)
        assertEquals(0, score.noisyCells)
        assertEquals(9, score.usableCells)
        assertEquals(1.0, score.usableShare!!, 1e-9)
        assertTrue("причин негодности нет", score.unfit.isEmpty())
    }

    @Test
    fun `непрочитанное не годно и считается отдельно от документа`() {
        val score = scoreUsable(
            "свалка",
            listOf(
                listOf("Артикул", "Кол-во"),
                listOf("11004", "120"),
                caption,
                listOf("Вийськове"),
                listOf("горович"),
                listOf("молодший"),
            ),
        )

        assertEquals("подпись в счёт ячеек не идёт", 4, score.documentCells)
        assertEquals(3, score.dumpCells)
        assertEquals(3, score.dumpRows)
        assertEquals(4.0 / 7.0, score.usableShare!!, 1e-9)
        assertTrue("больше трети листа — непрочитанное", Unfitness.DUMP in score.unfit)
    }

    /**
     * Измеритель мерит то, что получает человек (#1238). Своей доли непрочитанного у него нет:
     * судьбу таблицы у человека решает `unfitTable`, и порог непрочитанного один на обоих —
     * иначе прогон корпуса хвалит лист, который продукт человеку уже отдавать отказался.
     */
    @Test
    fun `непрочитанное меряется тем же порогом, каким продукт отказывает от таблицы`() {
        val cells = 12
        val unreadAtProductLimit = kotlin.math.ceil(cells * UNFIT_UNREAD_SHARE).toInt()
        val document = List(cells - unreadAtProductLimit) { listOf("значение") }
        val dump = List(unreadAtProductLimit) { listOf("непрочитанное") }

        val atLimit = scoreUsable("на пороге", document + listOf(caption) + dump)
        val below = scoreUsable(
            "ниже порога",
            document + listOf(listOf("значение")) + listOf(caption) + dump.drop(1),
        )

        assertTrue("продукт от такого листа отказывается, а измеритель его считает годным", Unfitness.DUMP in atLimit.unfit)
        assertFalse("измеритель ругает лист, который продукт человеку отдаёт", Unfitness.DUMP in below.unfit)
    }

    @Test
    fun `символьный шум движка виден числом`() {
        val noisy = listOf("[6", "_8.", "А4152_", "солдат'", "7,", "(31.07.2026", "[Mii 1 i", "Bийськова")
        noisy.forEach { assertTrue("«$it» — символьный шум", looksNoisy(it)) }

        val clean = listOf(
            "11004", "Гречка", "120", "кв.м.", "1.", "31.07.2026", "0,230", "А4152",
            "Пластівці вівсяні “Екстра”", "№ з/п", "ТОВ \"Мрія\"", "солдат", "(2 шт.)", "—",
        )
        clean.forEach { assertFalse("«$it» — обычное значение документа", looksNoisy(it)) }
    }

    @Test
    fun `метка неуверенности и шум в одной ячейке вычитаются один раз`() {
        val score = scoreUsable(
            "пересечение",
            listOf(
                listOf("Артикул", "Кол-во"),
                listOf("11004", "_120⚠"),
                listOf("11006", "40"),
            ),
        )

        assertEquals(1, score.flaggedCells)
        assertEquals(1, score.noisyCells)
        assertEquals(1, score.bothCells)
        assertEquals("одна плохая ячейка из шести", 5, score.usableCells)
    }

    @Test
    fun `предупреждённая ячейка годной не считается — её человек всё равно перепроверяет`() {
        val score = scoreUsable(
            "метки",
            listOf(listOf("Артикул", "Кол-во"), listOf("11004", "120⚠")),
        )

        assertEquals(3, score.usableCells)
        assertEquals(3.0 / 4.0, score.usableShare!!, 1e-9)
        assertTrue("одна метка из четырёх — четверть, стены нет", Unfitness.FLAGS !in score.unfit)
    }

    @Test
    fun `стена пометок названа причиной негодности, а не только низким числом`() {
        val rows = List(10) { listOf("11${it}0", "1,5⚠", "2,5⚠") }
        val score = scoreUsable("стена", listOf(listOf("Артикул", "Кг", "Шт")) + rows)

        assertEquals(20, score.flaggedCells)
        assertTrue("две трети листа помечены", Unfitness.FLAGS in score.unfit)
    }

    @Test
    fun `пустой файл не выдаётся за годный`() {
        val score = scoreUsable("пусто", listOf(listOf("", "")))

        assertEquals(0, score.cells)
        assertNull(score.usableShare)
        assertTrue(Unfitness.EMPTY in score.unfit)
    }

    @Test
    fun `на значениях, переписанных человеком, правило шума молчит`() {
        val dir = File(repo, "tools/corpus")
        val files = dir.listFiles { f: File -> f.name.endsWith(".expected.tsv") }.orEmpty().sortedBy { it.name }
        assertTrue("нет эталонов в ${dir.absolutePath}", files.isNotEmpty())

        val alarms = mutableListOf<String>()
        var values = 0
        files.forEach { file ->
            val expectation = parseTableExpectation(file.name.substringBefore('.'), file.readText())
            expectation.namedRows.forEach { row ->
                (row.cells.values + row.key).forEach { value ->
                    values++
                    if (looksNoisy(value)) alarms += "${file.name}: «$value»"
                }
            }
        }

        assertTrue("значений в эталонах не нашлось вовсе", values > 0)
        assertEquals("ложная тревога на человеческом тексте: $alarms", 0, alarms.size)
    }
}
