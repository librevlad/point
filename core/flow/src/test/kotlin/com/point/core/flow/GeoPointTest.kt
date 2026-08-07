package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoPointTest {

    @Test
    fun `пара координат становится фактом с происхождением «прочитано» и одной уликой формы`() {
        assertEquals(
            mapOf(
                META_ENTITY_GEO to "50.4501, 30.5234",
                META_ENTITY_GEO + META_SOURCE_SUFFIX to Provenance.OCR.wire,
                META_ENTITY_GEO + META_EVIDENCE_SUFFIX to "semantic",
            ),
            geoFacts("Точка зустрічі 50.4501, 30.5234"),
        )
    }

    @Test
    fun `разделителем бывает и пробел, и точка с запятой`() {
        assertEquals(listOf("50.4501, 30.5234"), geoPoints("50.4501 30.5234"))
        assertEquals(listOf("50.4501, 30.5234"), geoPoints("50.4501; 30.5234"))
    }

    @Test
    fun `южная широта и западная долгота — тоже точка на глобусе`() {
        assertEquals(listOf("-33.8688, 151.2093"), geoPoints("-33.8688, 151.2093"))
    }

    @Test
    fun `цены и количества координатами не становятся`() {

        assertTrue(geoPoints("12.500 45.300").isEmpty())
        assertTrue(geoPoints("Разом 8 970.00 грн").isEmpty())
    }

    @Test
    fun `за пределами глобуса координат не бывает`() {
        assertTrue(geoPoints("91.1234, 200.5678").isEmpty())
    }

    @Test
    fun `запятая как десятичный разделитель правилу не отдана — гадать оно не будет`() {

        assertTrue(geoPoints("50,4501, 30,5234").isEmpty())
    }

    @Test
    fun `числа без разделителя парой не становятся`() {
        assertTrue(geoPoints("50.450130.5234").isEmpty())
    }

    @Test
    fun `дата и версия — не координаты`() {
        assertTrue(geoPoints("від 08.06.2026").isEmpty())
        assertTrue(geoPoints("версія 1.2345.6789").isEmpty())
    }

    @Test
    fun `две точки на странице — вторая не прячется`() {
        val facts = geoFacts("Старт 50.4501, 30.5234\nФініш 49.8397, 24.0297")

        assertEquals("50.4501, 30.5234", facts[META_ENTITY_GEO])
        assertEquals(listOf("50.4501, 30.5234", "49.8397, 24.0297"), moreOf(facts, META_ENTITY_GEO))
    }

    @Test
    fun `нет координат — нет ключей, а не ключ с пустотой`() {
        assertTrue(geoFacts("вул. Сонячна, 15").isEmpty())
        assertTrue(geoFacts("").isEmpty())
    }

    @Test
    fun `форма координат судится одной реализацией — правилом и валидатором кандидатов`() {
        assertEquals(true, semanticFits(META_ENTITY_GEO, "50.4501, 30.5234"))
        assertEquals(false, semanticFits(META_ENTITY_GEO, "вул. Сонячна, 15"))
    }
}
