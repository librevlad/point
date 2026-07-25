package com.point.data

import com.point.core.flow.QrReader
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrEnricherTest {

    private fun imageObj() =
        PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `flags HAS_QR when the image decodes to something`() = runTest {
        val enricher = QrEnricher(object : QrReader { override suspend fun decode(imagePath: String) = "hi" })
        assertTrue(Feature.HAS_QR in enricher.enrich(imageObj()).features)
    }

    @Test
    fun `no flag when there is no QR`() = runTest {
        val enricher = QrEnricher(object : QrReader { override suspend fun decode(imagePath: String): String? = null })
        assertTrue(enricher.enrich(imageObj()).features.isEmpty())
    }

    @Test
    fun `applies only to images`() {
        val enricher = QrEnricher(object : QrReader { override suspend fun decode(imagePath: String): String? = null })
        assertTrue(enricher.appliesTo(ObjectState(ObjectKind.IMAGE)))
        assertFalse(enricher.appliesTo(ObjectState(ObjectKind.TEXT)))
    }
}
