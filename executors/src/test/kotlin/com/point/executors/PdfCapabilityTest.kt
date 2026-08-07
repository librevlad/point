package com.point.executors

import com.point.core.flow.capabilities.PdfCapability
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfCapabilityTest {

    private val cap = PdfCapability()

    @Test
    fun `offers extract-text for a normal PDF`() {
        assertTrue(cap.accepts(ObjectState(ObjectKind.PDF)))
    }

    @Test
    fun `hides extract-text for a scanned (image-only) PDF`() {
        val scan = ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF))

        assertFalse(cap.accepts(scan))
    }

    @Test
    fun `still converts image, text and office into a PDF`() {
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.OFFICE)))
    }
}
