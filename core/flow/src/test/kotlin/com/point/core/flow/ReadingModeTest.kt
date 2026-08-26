package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingModeTest {

    private fun atom(id: String, text: String, top: Float) =
        Atom(id, text, Box(10f, top, 200f, top + 18f), confidence = 0.9f)

    private val printed = AtomLayer(
        listOf(
            atom("w1", "Трек-номер", 10f), atom("w2", "20", 40f),
            atom("w3", "4514", 40f), atom("w4", "9154", 40f), atom("w5", "9395", 40f),
        ),
    )

    @Test
    fun `слой со словами — печать`() {
        assertEquals(ReadingMode.PRINTED, readingModeOf(printed))
        assertTrue(printedGuarantees(readingModeOf(printed)))
    }

    @Test
    fun `пустой слой — рукопись, а не печать по умолчанию`() {
        assertEquals(ReadingMode.HANDWRITTEN, readingModeOf(AtomLayer(emptyList())))
    }

    @Test
    fun `символьная каша — рукопись, читать будет модель глазами`() {
        val soup = AtomLayer((0 until 12).map { atom("g$it", "|//~", it * 20f) })

        assertEquals(ReadingMode.HANDWRITTEN, readingModeOf(soup))
        assertFalse("печатных гарантий на рукописи нет", printedGuarantees(readingModeOf(soup)))
    }

    @Test
    fun `слоя нет — не знаем, и не врём ни в одну сторону`() {
        assertEquals(ReadingMode.UNKNOWN, readingModeOf(null as AtomLayer?))
        assertFalse(printedGuarantees(ReadingMode.UNKNOWN))
    }

    private fun ocr(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/ocr/$name.txt")) { "нет образца $name" }
            .bufferedReader().readText()

    @Test
    fun `дословная каша ведомости — рукопись по уверенности движка, а не по доле букв`() {
        val soup = ocr("ledger_23")
        val words = soup.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val page = AtomLayer(
            words.mapIndexed { i, w -> Atom("w$i", w, Box(10f, i * 20f, 200f, i * 20f + 18f), confidence = 0.35f) },
        )

        assertFalse("состав символов кашу пропускает — на этом признаке всё и ломалось", looksLikeOcrGarbage(soup))
        assertEquals("а движок про себя говорит правду", ReadingMode.HANDWRITTEN, readingModeOfFrame(page, soup))
        assertFalse(printedGuarantees(readingModeOfFrame(page, soup)))
    }

    @Test
    fun `читаемый текст без геометрии — по-прежнему не знаем`() {
        assertEquals(ReadingMode.UNKNOWN, readingModeOfFrame(null, ocr("ledger_23")))
    }

    @Test
    fun `пустой и мусорный вывод ридера без геометрии — рукопись, читать будет модель`() {
        assertEquals(ReadingMode.HANDWRITTEN, readingModeOfFrame(null, "   "))
        assertEquals(ReadingMode.HANDWRITTEN, readingModeOfFrame(null, "|//~ ]{} ".repeat(6)))
    }

    @Test
    fun `режим переживает журнал метаданными`() {
        val saved = mapOf(META_READING_MODE to ReadingMode.HANDWRITTEN.name)

        assertEquals(ReadingMode.HANDWRITTEN, readingModeOf(saved))
        assertEquals(ReadingMode.UNKNOWN, readingModeOf(mapOf(META_READING_MODE to "нечто")))
        assertEquals(ReadingMode.UNKNOWN, readingModeOf(emptyMap()))
    }

    @Test
    fun `рукопись не даёт печатных подтверждений — максимум форма значения`() {
        val soup = AtomLayer((0 until 12).map { atom("g$it", "|//~", it * 20f) })

        val evidence = soup.fieldEvidence(
            META_ENTITY_TRACK,
            FieldCandidate("20 4514 9154 9395"),
        )

        assertTrue(evidence.size < CONFIRMED_CLASSES)
        assertFalse(EvidenceClass.STRUCTURAL in evidence)
        assertFalse(EvidenceClass.GEOMETRIC in evidence)
    }
}
