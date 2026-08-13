package com.point.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceTest {

    @Test
    fun `порядок объявления — сила происхождения`() {

        assertTrue(Provenance.HUMAN > Provenance.OCR)
        assertTrue(Provenance.OCR > Provenance.RULE)
        assertTrue(Provenance.RULE > Provenance.MODEL)
        assertTrue(Provenance.MODEL > Provenance.GIVEN)
        assertTrue("«дано» должно быть сильнее, чем «неизвестно»", Provenance.GIVEN > Provenance.UNKNOWN)
    }

    @Test
    fun `«никто не читал» — слабейшее, а не среднее`() {

        assertEquals(Provenance.UNKNOWN, Provenance.entries.min())
        assertEquals(Provenance.HUMAN, Provenance.entries.max())
    }

    @Test
    fun `слово переживает журнал — round-trip через wire`() {
        Provenance.entries.forEach { assertEquals(it, provenanceOf(it.wire)) }
    }

    /**
     * Отсутствие происхождения — это «неизвестно», а не «дано» (#948).
     *
     * Прежде пустота молча означала `GIVEN`: значение, вычитанное OCR-ом с уличного снимка,
     * записывалось так же, как введённое человеком руками, и выглядело на экране спокойнее
     * всего. Самое сомнительное знание получало самый уверенный вид.
     */
    @Test
    fun `незнакомое слово и пустота — «неизвестно», а не «дано»`() {

        assertEquals(Provenance.UNKNOWN, provenanceOf(null))
        assertEquals(Provenance.UNKNOWN, provenanceOf(""))
        assertEquals(Provenance.UNKNOWN, provenanceOf("llm"))
        assertEquals(Provenance.UNKNOWN, provenanceOf("OCR"))
    }

    @Test
    fun `у объекта по умолчанию происхождения нет — файл принёс человек, читать его никто не читал`() {
        val shared = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

        assertEquals(Provenance.GIVEN, shared.provenance)
    }
}
