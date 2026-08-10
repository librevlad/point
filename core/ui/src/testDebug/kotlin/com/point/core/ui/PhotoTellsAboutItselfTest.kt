package com.point.core.ui

import com.point.core.flow.META_ENTITY_GEO
import com.point.core.flow.META_SHOT_AT
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Снимок показывает, что знает о себе (#547): дату съёмки и место — наравне с остальным
 * понятым. Раньше человек шёл за этим в файловый менеджер.
 */
class PhotoTellsAboutItselfTest {

    private fun photo(features: Set<Feature>, metadata: Map<String, String>) = PointObject(
        id = "id",
        mime = "image/jpeg",
        uri = ScratchRef("/scratch/снимок.jpg"),
        state = ObjectState(ObjectKind.IMAGE, features),
        metadata = metadata,
    )

    @Test fun `дата съёмки и место видны как факты объекта`() {
        val facts = understoodFacts(
            photo(
                setOf(Feature.HAS_SHOT_AT, Feature.HAS_GEO),
                mapOf(META_SHOT_AT to "12.03.2024, 14:07", META_ENTITY_GEO to "50.45010, 30.52340"),
            ),
        )

        assertEquals("12.03.2024, 14:07", facts.first { it.key == "shot-at" }.value)
        assertEquals("50.45010, 30.52340", facts.first { it.key == "geo" }.value)
    }

    @Test fun `у снимка без записей ничего не выдумывается`() {
        val facts = understoodFacts(photo(emptySet(), emptyMap()))

        assertTrue("выдуманы факты на пустом месте", facts.none { it.key == "shot-at" || it.key == "geo" })
    }

    @Test fun `координаты из текста показываются той же строкой`() {
        // Одна строка на оба источника: и когда координаты нашла модель, и когда они из снимка.
        val facts = understoodFacts(
            photo(emptySet(), mapOf(META_ENTITY_GEO to "49.98410, 36.25270")),
        )

        assertEquals("49.98410, 36.25270", facts.first { it.key == "geo" }.value)
    }
}
