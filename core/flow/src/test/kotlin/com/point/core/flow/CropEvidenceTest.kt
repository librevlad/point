package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CropEvidenceTest {

    private fun atom(text: String, x: Float, y: Float, width: Float = 60f) =
        Atom(id = "a${x.toInt()}-${y.toInt()}", text = text, box = Box(x, y, x + width, y + 20f))

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

    /**
     * Длинный снимок экрана, прочитанный построчно: атом на всю строку (#1013).
     *
     * Так читает движок, который берётся за крупные кадры, — и почта из середины строки
     * своего атома не имеет.
     */
    private val longShot = AtomLayer(
        (1..120).map { n ->
            val line = "Line %03d order OR-01%03d sum 1%02d.%02d contact tester%d@example.com"
                .format(n, n, n, n, n)
            Atom(id = "w$n", text = line, box = Box(15f, n * 100f, 900f, n * 100f + 26f))
        },
    )

    @Test
    fun `читатель отдал строку одним куском — место значения всё равно известно (#1013)`() {
        val region = longShot.locate("tester77@example.com")

        assertNotNull("к найденному нельзя перейти, пока у него нет места на снимке", region)
        assertTrue("место стоит на своей строке", region!!.holds(500f, 7700f + 13f))
        assertTrue("а не на соседней", !region.holds(500f, 7800f + 13f))
        assertTrue("и не на середине документа", !region.holds(500f, 6000f + 13f))
    }

    @Test
    fun `слово, встречающееся в каждой строке, места по-прежнему не даёт`() {
        assertNull("«contact» стоит везде — такой адрес человеку ничего не говорит", longShot.locate("contact"))
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

    private val sidewaysFrame =
        FrameTransform(sample = 2, rotationDegrees = 90, uprightWidth = 400, uprightHeight = 600)

    private fun rawAtom(text: String, x: Float, y: Float, width: Float = 60f) =
        Atom(id = "$text-$x-$y", text = text, box = sidewaysFrame.toRaw(Box(x, y, x + width, y + 20f)))

    private val sidewaysSheet = AtomLayer(
        listOf(
            rawAtom("11004", 0f, 100f), rawAtom("Гречка", 100f, 100f), rawAtom("50", 300f, 100f),
            rawAtom("11006", 0f, 200f), rawAtom("Рис", 100f, 200f), rawAtom("40", 300f, 200f),
        ),
        transform = sidewaysFrame,
    )

    @Test
    fun `улика режется из сырого кадра и знает, на сколько её довернуть`() {
        val out = listOf(uncertain("11004 Гречка 50")).withCropEvidence(sidewaysSheet, "/tmp/23.jpg")

        assertEquals(90, out.single().evidence!!.uprightDegrees)
    }

    @Test
    fun `кадр снят боком — адресуется строка страницы, а не колонка сырого кадра`() {
        val region = sidewaysSheet.locate("11004 Гречка 50 кг")

        assertNotNull("строка бокового кадра обязана адресоваться так же, как ровного", region)
        val mine = sidewaysFrame.toRaw(Box(0f, 100f, 360f, 120f))
        val neighbour = sidewaysFrame.toRaw(Box(0f, 200f, 360f, 220f))
        assertTrue("улика накрывает свою строку целиком", region!!.holds(mine.centerX, mine.centerY))
        assertTrue("вместе с крайним словом строки", region.holds(mine.left + 1f, mine.top + 1f))
        assertTrue("и не залезает в соседнюю", !region.holds(neighbour.centerX, neighbour.centerY))
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
    fun `помеченное без адреса названо вслух так же, как обрезанное пределом`() {
        val blocks = listOf(uncertain("11004 Гречка 50"), uncertain("Итого по ведомости 2400"))

        val out = blocks.withCropEvidence(sheet, "/tmp/23.jpg")

        assertNotNull("у строки с печатным артикулом место на снимке есть", out[0].evidence)
        assertNull("у итога — нет, такой строки движок не собрал", out[1].evidence)
        assertEquals("значит, про него сказано вслух", blocks.size + 1, out.size)
        assertTrue("иначе картинка была бы у одного, а второй читался бы как бесспорный", out.last().text.contains("1 из 2"))
    }

    @Test
    fun `в предел укладываемся — приписки нет`() {
        val out = listOf(uncertain("11004 Гречка 50")).withCropEvidence(sheet, "/tmp/23.jpg")

        assertEquals(1, out.size)
    }

    @Test
    fun `кусок для глаз и кусок для чтения — разные назначения одного адреса`() {
        val glance = CropEvidence("/tmp/23.jpg", Box(0f, 0f, 10f, 10f))

        assertEquals(CropPurpose.GLANCE, glance.purpose)
        assertEquals(CropPurpose.READING, glance.copy(purpose = CropPurpose.READING).purpose)
    }

    @Test
    fun `полосу строки с фотографии бумаги увеличивать не надо`() {

        assertEquals(1, readingCropUpscale(3500, 150))
        assertEquals(1, readingCropUpscale(3500, READING_BAND_PX))
    }

    @Test
    fun `строка снимка экрана поднимается до читаемой высоты`() {

        assertEquals(4, readingCropUpscale(900, 25))
        assertEquals(3, readingCropUpscale(900, 41))
        assertEquals(2, readingCropUpscale(900, 61))
    }

    @Test
    fun `увеличение целое и только вверх — интерполяция не рисует того, чего нет`() {

        assertEquals(4, readingCropUpscale(900, 1))

        assertEquals(1, readingCropUpscale(0, 0))
        assertEquals(1, readingCropUpscale(900, 0))
    }

    @Test
    fun `бюджет памяти отступает последним, но отступить обязан`() {

        assertEquals(4, readingCropUpscale(widthPx = 4000, heightPx = 30))
        assertEquals(2, readingCropUpscale(widthPx = 4000, heightPx = 30, budgetPx = 1_000_000L))

        assertEquals(1, readingCropUpscale(widthPx = 4000, heightPx = 60, budgetPx = 1L))
    }
}
