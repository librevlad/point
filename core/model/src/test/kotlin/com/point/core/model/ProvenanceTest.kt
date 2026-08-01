package com.point.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Происхождение вместо уверенности (#264): словарь полон, порядок объявления = сила,
 * слово переживает журнал.
 */
class ProvenanceTest {

    @Test
    fun `порядок объявления — сила происхождения`() {
        // Правило «происхождение не понижается» (#261) пишется сравнением enum'а, а не таблицей
        // рангов: одна лестница, а не две, которые разъедутся на первой правке.
        assertTrue(Provenance.HUMAN > Provenance.OCR)
        assertTrue(Provenance.OCR > Provenance.RULE)
        assertTrue(Provenance.RULE > Provenance.MODEL)
        assertTrue(Provenance.MODEL > Provenance.GIVEN)
    }

    @Test
    fun `«никто не читал» — слабейшее, а не среднее`() {
        // GIVEN заменяет прежний sourceRank(null) == -1: «.src не записан» и «никто не читал» —
        // одно состояние, и оно обязано проигрывать любому настоящему чтению.
        assertEquals(Provenance.GIVEN, Provenance.entries.min())
        assertEquals(Provenance.HUMAN, Provenance.entries.max())
    }

    @Test
    fun `слово переживает журнал — round-trip через wire`() {
        Provenance.entries.forEach { assertEquals(it, provenanceOf(it.wire)) }
    }

    @Test
    fun `незнакомое слово и пустота — GIVEN, а не падение и не выдуманное чтение`() {
        // Легаси-журнал, записанный до #264, `.src` не содержит вовсе. Подписи не будет —
        // и это «не знаем», а не ложь.
        assertEquals(Provenance.GIVEN, provenanceOf(null))
        assertEquals(Provenance.GIVEN, provenanceOf(""))
        assertEquals(Provenance.GIVEN, provenanceOf("llm"))
        assertEquals(Provenance.GIVEN, provenanceOf("OCR")) // регистр — часть слова, не намёк
    }

    @Test
    fun `у объекта по умолчанию происхождения нет — файл принёс человек, читать его никто не читал`() {
        val shared = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

        assertEquals(Provenance.GIVEN, shared.provenance)
    }
}
