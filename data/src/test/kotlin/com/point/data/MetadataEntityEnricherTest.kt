package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataEntityEnricherTest {

    private fun obj(metadata: Map<String, String>, kind: ObjectKind = ObjectKind.TEXT) =
        PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(kind), metadata)

    @Test
    fun `lights features for stored entity values`() = runTest {
        val features = MetadataEntityEnricher().enrich(
            obj(
                mapOf(
                    META_ENTITY_PREFIX + "phone" to "+380671234567",
                    META_ENTITY_PREFIX + "url" to "https://a.example",
                    META_ENTITY_PREFIX + "qr" to "wifi:...",
                ),
            ),
        ).features
        assertEquals(setOf(Feature.HAS_PHONE, Feature.HAS_URL), features)
    }

    @Test
    fun `applies to every kind and is instant`() {
        val enricher = MetadataEntityEnricher()
        assertTrue(enricher.appliesTo(ObjectState(ObjectKind.IMAGE)))
        assertTrue(enricher.appliesTo(ObjectState(ObjectKind.PDF)))
        assertEquals(EnrichCost.INSTANT, enricher.meta.cost)
    }

    @Test
    fun `no stored facts - no features`() = runTest {
        assertTrue(MetadataEntityEnricher().enrich(obj(emptyMap())).features.isEmpty())
    }

    @Test
    fun `a stored semantic type lights its IS feature (#89)`() = runTest {
        val features = MetadataEntityEnricher().enrich(
            obj(mapOf(com.point.core.flow.META_SEMANTIC_TYPE to "recipe")),
        ).features
        assertEquals(setOf(Feature.IS_RECIPE), features)
    }

    @Test
    fun `сохранённый слой слов зажигает признак поиска после перезапуска`() = runTest {
        val offline = MetadataEntityEnricher()
            .enrich(obj(mapOf(com.point.core.flow.META_OCR_ATOMS_REF to "/scratch/atoms.tsv")))
        assertEquals(setOf(Feature.HAS_WORD_LAYER), offline.features)

        val cloud = MetadataEntityEnricher()
            .enrich(obj(mapOf(com.point.core.flow.META_CLOUD_ATOMS_REF to "/scratch/cloud.tsv")))
        assertEquals(setOf(Feature.HAS_WORD_LAYER), cloud.features)
    }

    @Test
    fun `an unknown semantic type stays silent`() = runTest {
        assertTrue(
            MetadataEntityEnricher().enrich(
                obj(mapOf(com.point.core.flow.META_SEMANTIC_TYPE to "poem")),
            ).features.isEmpty(),
        )
    }
}
