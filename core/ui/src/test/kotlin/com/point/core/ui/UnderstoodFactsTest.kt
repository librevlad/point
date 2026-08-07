package com.point.core.ui

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderstoodFactsTest {

    private fun obj(features: Set<Feature> = emptySet(), metadata: Map<String, String> = emptyMap()) =
        PointObject("id", "image/png", ScratchRef("/x"), ObjectState(ObjectKind.IMAGE, features), metadata)

    @Test
    fun `orders facts stably and pairs labels with values`() {
        val facts = understoodFacts(
            obj(
                features = setOf(Feature.HAS_URL, Feature.HAS_PHONE),
                metadata = mapOf(
                    "entity.phone" to "+380671234567",
                    "entity.url" to "https://github.com/librevlad",
                ),
            ),
        )
        assertEquals(listOf("Нашёл телефон", "Нашёл ссылку"), facts.map { it.label })
        assertEquals("+380671234567", facts[0].value)
        assertEquals("github.com/librevlad", facts[1].value)
    }

    @Test
    fun `masks a card number down to its last four digits`() {
        val facts = understoodFacts(obj(setOf(Feature.HAS_CARD), mapOf("entity.card" to "5375 4141 1234 5678")))
        assertEquals("Нашёл карту", facts.single().label)
        assertEquals("•• 5678", facts.single().value)
    }

    @Test
    fun `a feature without a stored value still yields a labelled fact`() {
        val facts = understoodFacts(obj(setOf(Feature.HAS_DATE)))
        assertEquals("Нашёл дату", facts.single().label)
        assertNull(facts.single().value)
    }

    @Test
    fun `describes featureful states without entities`() {
        assertEquals(
            "Это скан — текст не выделяется",
            understoodFacts(obj(setOf(Feature.IS_IMAGE_PDF))).single().label,
        )
        assertEquals(
            "Архив из фотографий",
            understoodFacts(obj(setOf(Feature.ZIP_OF_IMAGES))).single().label,
        )
    }

    @Test
    fun `an empty state yields no facts`() {
        assertTrue(understoodFacts(obj()).isEmpty())
    }
}
