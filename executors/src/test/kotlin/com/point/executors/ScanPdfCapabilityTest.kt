package com.point.executors

import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-gesture "collection of photos → clean PDF" capability declaration. */
class ScanPdfCapabilityTest {

    private val cap = ScanPdfCapability()

    @Test
    fun `accepts a collection, not a single image`() {
        assertTrue(cap.accepts(ObjectState(ObjectKind.COLLECTION)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `produces a PDF and so PREPAREs it`() {
        assertEquals(ObjectKind.PDF, cap.produces(ObjectState(ObjectKind.COLLECTION))?.kind)
        assertEquals(setOf(Intent.PREPARE), cap.intents(ObjectState(ObjectKind.COLLECTION)))
    }
}
