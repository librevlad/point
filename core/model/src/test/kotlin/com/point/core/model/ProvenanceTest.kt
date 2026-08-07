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
    }

    @Test
    fun `«никто не читал» — слабейшее, а не среднее`() {

        assertEquals(Provenance.GIVEN, Provenance.entries.min())
        assertEquals(Provenance.HUMAN, Provenance.entries.max())
    }

    @Test
    fun `слово переживает журнал — round-trip через wire`() {
        Provenance.entries.forEach { assertEquals(it, provenanceOf(it.wire)) }
    }

    @Test
    fun `незнакомое слово и пустота — GIVEN, а не падение и не выдуманное чтение`() {

        assertEquals(Provenance.GIVEN, provenanceOf(null))
        assertEquals(Provenance.GIVEN, provenanceOf(""))
        assertEquals(Provenance.GIVEN, provenanceOf("llm"))
        assertEquals(Provenance.GIVEN, provenanceOf("OCR"))
    }

    @Test
    fun `у объекта по умолчанию происхождения нет — файл принёс человек, читать его никто не читал`() {
        val shared = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

        assertEquals(Provenance.GIVEN, shared.provenance)
    }
}
