package com.point.core.ui

import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The hero verdict: WHAT the object is, degrading from kind to semantic type as AI deepens (#114). */
class ObjectVerdictTest {

    private fun obj(
        kind: ObjectKind = ObjectKind.IMAGE,
        features: Set<Feature> = emptySet(),
        metadata: Map<String, String> = emptyMap(),
    ) = PointObject("id", "mime", ScratchRef("/x"), ObjectState(kind, features), metadata)

    @Test
    fun `falls back to the kind label when nothing is understood`() {
        val o = obj(kind = ObjectKind.IMAGE)
        assertEquals(kindLabel(ObjectKind.IMAGE), objectVerdict(o).headline)
        assertNull(objectVerdict(o).subline)
    }

    @Test
    fun `a recognised purchase leads with its human verdict`() {
        assertEquals("Покупка", objectVerdict(obj(features = setOf(Feature.IS_PURCHASE))).headline)
    }

    @Test
    fun `a vcard is a visiting card, not just an image`() {
        assertEquals("Визитка", objectVerdict(obj(features = setOf(Feature.HAS_VCARD))).headline)
    }

    @Test
    fun `the AI summary becomes the subline`() {
        val o = obj(
            features = setOf(Feature.IS_RECIPE),
            metadata = mapOf(META_SEMANTIC_SUMMARY to "Борщ на говяжьем бульоне"),
        )
        assertEquals("Рецепт", objectVerdict(o).headline)
        assertEquals("Борщ на говяжьем бульоне", objectVerdict(o).subline)
    }

    @Test
    fun `without a summary the filename fills the subline`() {
        val o = obj(metadata = mapOf("name" to "чек.jpg"))
        assertEquals(kindLabel(ObjectKind.IMAGE), objectVerdict(o).headline)
        assertEquals("чек.jpg", objectVerdict(o).subline)
    }
}
