package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подпись колонки остаётся при своей колонке (#768).
 *
 * На почтовой наклейке, снятой с наклоном, «КОМУ:» физически выше «ВІД:» — и попадает в
 * отдельную строку. Просвет между столбцами такая одиночная строка образовать не может:
 * написанное в ней стоит только с одной стороны. Строка выпадала из полосы отдельным блоком,
 * модель читала её сверху вниз и приписывала «КОМУ» левому столбцу — отправитель и получатель
 * менялись местами.
 *
 * Владелец о цене ошибки: знание должно быть либо верным, либо отсутствовать. Здесь Point
 * уверенно утверждал неверное и строил на этом «Сохранить контакт» и «Построить маршрут».
 */
class ColumnCaptionKeepsItsColumnTest {

    private var next = 0

    private fun atom(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float = 1f,
    ) = Atom(id = "a${next++}", text = text, box = Box(left, top, right, bottom), confidence = confidence)

    /**
     * Наклейка с наклоном: «КОМУ:» на 25 точек выше «ВІД:», поперёк просвета висит волосяная
     * черта — сгиб бумаги, прочитанный уверенно, — а над столбцами стоит шапка во всю ширину.
     */
    private val label = AtomLayer(
        listOf(
            atom("ОДЕСА ПОСИЛКОВИЙ", 100f, 100f, 900f, 150f),
            atom("БІЛГОРОД-ДНІСТРОВСЬКИЙ", 150f, 170f, 850f, 220f),

            atom("КОМУ:", 520f, 250f, 640f, 280f),
            atom("|", 480f, 250f, 483f, 285f, confidence = 0.85f),

            atom("ВІД: 29.07/12:59", 100f, 275f, 450f, 305f),

            atom("Тарасенко Світлана Сергіївна", 100f, 320f, 450f, 350f),
            atom("Думброван Олександр", 520f, 320f, 900f, 350f),

            atom("м.Дніпро, Відділення №14", 100f, 380f, 450f, 410f),
            atom("с.Бритівка (Одеська обл.),", 520f, 380f, 900f, 410f),

            atom("067 636 05 60", 100f, 440f, 450f, 470f),
        ),
    )

    private fun blockWith(needle: String): String =
        label.blockTexts().single { needle in it }

    @Test
    fun `КОМУ стоит в блоке получателя, а не отдельно`() {
        val block = blockWith("КОМУ:")

        assertTrue(block, "Думброван Олександр" in block)
        assertTrue(block, "Тарасенко Світлана Сергіївна" !in block)
    }

    @Test
    fun `ВІД стоит в блоке отправителя вместе с его телефоном`() {
        val block = blockWith("ВІД: 29.07/12:59")

        assertTrue(block, "Тарасенко Світлана Сергіївна" in block)
        assertTrue(block, "067 636 05 60" in block)
        assertTrue(block, "Думброван Олександр" !in block)
    }

    /**
     * Прошлый заход по этой карточке откатили именно из-за шапки: притягивая подписи к
     * колонкам, он утащил «БІЛГОРОД-ДНІСТРОВСЬКИЙ» в правый столбец — одна ошибка менялась
     * на другую.
     */
    @Test
    fun `шапку во всю ширину в столбец не утаскивает`() {
        val header = blockWith("БІЛГОРОД-ДНІСТРОВСЬКИЙ")

        assertTrue(header, "Думброван Олександр" !in header)
        assertTrue(header, "Тарасенко Світлана Сергіївна" !in header)
        assertTrue(header, "КОМУ:" !in header)
    }

    /**
     * Настоящая наклейка: 81 слово с телефона, «КОМУ:» на 50 точек выше «В1Д:», обрывки
     * распознавания по всему полю. Прошлый заход по этой карточке выглядел верным на
     * сочинённой раскладке и разваливался здесь — поэтому проверка идёт на фикстуре.
     */
    private val real = AtomCodec.decode(
        checkNotNull(javaClass.getResourceAsStream("/ocr/np_label.atoms.tsv")) {
            "нет фикстуры наклейки"
        }.bufferedReader().readText(),
    )

    @Test
    fun `на настоящей наклейке КОМУ стоит при получателе`() {
        val block = real.blockTexts().single { "КОМУ" in it }

        assertTrue(block, "умброван" in block)
        assertTrue(block, "Тарасенко" !in block)
    }

    @Test
    fun `на настоящей наклейке шапка в столбец не уезжает`() {
        val header = real.blockTexts().single { "ЛГОРОД" in it }

        assertTrue(header, "умброван" !in header)
        assertTrue(header, "Тарасенко" !in header)
    }

    @Test
    fun `на странице без столбцов строки остаются там, где стояли`() {
        val plain = AtomLayer(
            listOf(
                atom("Накладна", 100f, 100f, 400f, 130f),
                atom("від 30.03.2026", 100f, 140f, 400f, 170f),
                atom("на суму 2 148,30", 100f, 180f, 400f, 210f),
            ),
        )

        assertEquals(listOf("Накладна", "від 30.03.2026", "на суму 2 148,30"), plain.blockTexts())
    }
}
