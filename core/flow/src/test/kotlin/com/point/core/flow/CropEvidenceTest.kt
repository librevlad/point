package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Адрес кроп-улики (#267, вторая половина): к какому месту страницы относится спорный фрагмент
 * документа и когда честнее не показывать ничего.
 *
 * Прецедент — продовольственная ведомость владельца: ~35 строк, в первой колонке печатные
 * артикулы (11004, 11006, 11012…), поверх печати рукописные пометки. Движок телефона на этом
 * кадре собирает кашу, но артикул из первой колонки читает — и он остаётся единственной ниткой
 * от строки документа к строке бумаги.
 */
class CropEvidenceTest {

    private fun atom(text: String, x: Float, y: Float, width: Float = 60f) =
        Atom(id = "a${x.toInt()}-${y.toInt()}", text = text, box = Box(x, y, x + width, y + 20f))

    /** Три строки ведомости: шапка и две позиции. */
    private val sheet = AtomLayer(
        listOf(
            atom("Артикул", 0f, 0f), atom("Наименование", 100f, 0f), atom("Кол", 300f, 0f),
            atom("11004", 0f, 100f), atom("Гречка", 100f, 100f), atom("50", 300f, 100f),
            atom("11006", 0f, 200f), atom("Рис", 100f, 200f), atom("40", 300f, 200f),
        ),
    )

    private fun Box.holds(x: Float, y: Float) = contains(x, y)

    @Test
    fun `фрагмент адресуется строкой, слова которой с ним совпали`() {
        val region = sheet.locate("11004 Гречка 50 кг")

        assertNotNull(region)
        assertTrue("улика обязана накрыть свою строку", region!!.holds(130f, 110f))
        assertTrue("и не залезать в соседнюю", !region.holds(130f, 210f))
    }

    @Test
    fun `одного длинного слова хватает — артикул адресует строку, где остальное каша`() {
        val garbled = AtomLayer(
            listOf(
                atom("11004", 0f, 100f), atom("3}3/9I=I", 100f, 100f), atom("-(8}-I8)", 200f, 100f),
                atom("11006", 0f, 200f), atom("=I=I-(8", 100f, 200f), atom("I8)-8}", 200f, 200f),
            ),
        )

        val region = garbled.locate("11004 Гречка 50 кг")

        assertNotNull(region)
        assertTrue(region!!.holds(30f, 110f))
        assertTrue(!region.holds(30f, 210f))
    }

    @Test
    fun `одного короткого слова не хватает — по «50» строка не опознаётся`() {
        assertNull(sheet.locate("50"))
    }

    @Test
    fun `ничья двух строк адресом не считается`() {
        val twice = AtomLayer(
            listOf(
                atom("Гречка", 0f, 100f), atom("Гречка", 0f, 200f),
            ),
        )

        assertNull("одно и то же слово в двух строках не адресует ни одну", twice.locate("Гречка 50"))
    }

    @Test
    fun `слова со страницы нет — улики нет`() {
        assertNull(sheet.locate("Итого по ведомости"))
    }

    // -- политика: кому улику прикладываем --

    private fun uncertain(text: String) = DocBlock(text, DocStyle.NORMAL, uncertain = true)

    @Test
    fun `координат нет — список тот же, и это не ошибка`() {
        val blocks = listOf(uncertain("11004 Гречка 50"))

        assertSame(blocks, blocks.withCropEvidence(layer = null, imagePath = "/tmp/23.jpg"))
        assertSame(blocks, blocks.withCropEvidence(sheet, imagePath = null))
        assertSame(blocks, blocks.withCropEvidence(AtomLayer(emptyList()), "/tmp/23.jpg"))
    }

    @Test
    fun `улика идёт только к помеченному фрагменту`() {
        val blocks = listOf(
            DocBlock("11004 Гречка 50", DocStyle.NORMAL),
            uncertain("11006 Рис 40"),
        )

        val out = blocks.withCropEvidence(sheet, "/tmp/23.jpg")

        assertNull("уверенное картинкой не подпирают — она только растит файл", out[0].evidence)
        assertNotNull(out[1].evidence)
        assertEquals("/tmp/23.jpg", out[1].evidence!!.imagePath)
    }

    @Test
    fun `улика режется из сырого кадра и знает, на сколько её довернуть`() {
        val sideways = AtomLayer(sheet.atoms, transform = FrameTransform(sample = 2, rotationDegrees = 90, uprightWidth = 400, uprightHeight = 300))

        val out = listOf(uncertain("11004 Гречка 50")).withCropEvidence(sideways, "/tmp/23.jpg")

        assertEquals(90, out.single().evidence!!.uprightDegrees)
    }

    @Test
    fun `предел на документ соблюдается, а обрезанный остаток назван вслух`() {
        val rows = (0 until 20).map { atom("1100$it", 0f, it * 100f) to atom("товар", 100f, it * 100f) }
        val layer = AtomLayer(rows.flatMap { listOf(it.first, it.second) })
        val blocks = (0 until 20).map { uncertain("1100$it товар") }

        val out = blocks.withCropEvidence(layer, "/tmp/23.jpg")

        assertEquals(MAX_EVIDENCE_CROPS, out.count { it.evidence != null })
        assertEquals("абзац-приписка добавляется ровно один", blocks.size + 1, out.size)
        assertTrue(out.last().text.contains("$MAX_EVIDENCE_CROPS из 20"))
        assertTrue("приписка — наша, а не прочитанное", !out.last().uncertain)
    }

    @Test
    fun `в предел укладываемся — приписки нет`() {
        val out = listOf(uncertain("11004 Гречка 50")).withCropEvidence(sheet, "/tmp/23.jpg")

        assertEquals(1, out.size)
    }
}
