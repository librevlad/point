package com.point.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectStateTest {

    @Test
    fun `with adds a feature and is immutable`() {
        val base = ObjectState(ObjectKind.PDF)
        val enriched = base.with(Feature.HAS_URL)

        assertTrue(enriched.has(Feature.HAS_URL))
        assertFalse("original state must not be mutated", base.has(Feature.HAS_URL))
    }
}
