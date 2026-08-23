package com.point.data

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

class MetadataEntityInvestigationTest {

    private fun obj(metadata: Map<String, String>, kind: ObjectKind = ObjectKind.TEXT) =
        PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(kind), metadata)

    @Test
    fun `lights features for stored entity values`() = runTest {
        val features = MetadataEntityInvestigationRealizer().look(
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
        val enricher = MetadataEntityInvestigationRealizer()
        assertTrue(MetadataEntityInvestigation().accepts(ObjectState(ObjectKind.IMAGE)))
        assertTrue(MetadataEntityInvestigation().accepts(ObjectState(ObjectKind.PDF)))
        assertEquals(com.point.core.flow.Latency.INSTANT, MetadataEntityInvestigation().meta.latency)
    }

    @Test
    fun `no stored facts - no features`() = runTest {
        assertTrue(MetadataEntityInvestigationRealizer().look(obj(emptyMap())).features.isEmpty())
    }

    @Test
    fun `a stored semantic type lights its IS feature (#89)`() = runTest {
        val features = MetadataEntityInvestigationRealizer().look(
            obj(mapOf(com.point.core.flow.META_SEMANTIC_TYPE to "recipe")),
        ).features
        assertEquals(setOf(Feature.IS_RECIPE), features)
    }

    @Test
    fun `сохранённый слой слов зажигает признак поиска после перезапуска`() = runTest {
        val offline = MetadataEntityInvestigationRealizer()
            .look(obj(mapOf(com.point.core.flow.META_OCR_ATOMS_REF to "/scratch/atoms.tsv")))
        assertEquals(setOf(Feature.HAS_WORD_LAYER), offline.features)
    }

    @Test
    fun `an unknown semantic type stays silent`() = runTest {
        assertTrue(
            MetadataEntityInvestigationRealizer().look(
                obj(mapOf(com.point.core.flow.META_SEMANTIC_TYPE to "poem")),
            ).features.isEmpty(),
        )
    }
}
