package com.point.data

import com.point.core.flow.PdfTextExtractor
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfImageInvestigationTest {

    private fun extractorOf(text: String) = object : PdfTextExtractor {
        override suspend fun extractText(obj: PointObject) = text
    }

    private val pdf = PointObject(
        id = "id",
        mime = "application/pdf",
        uri = ScratchRef("/scratch/x.pdf"),
        state = ObjectState(ObjectKind.PDF),
    )

    @Test
    fun `flags IS_IMAGE_PDF when the PDF has no text layer`() = runTest {
        val features = PdfImageInvestigationRealizer(extractorOf("   \n  \t ")).look(pdf).features
        assertTrue(Feature.IS_IMAGE_PDF in features)
    }

    @Test
    fun `no flag when the PDF has extractable text`() = runTest {
        val features = PdfImageInvestigationRealizer(extractorOf("Договор №42 от 2026 года")).look(pdf).features
        assertFalse(Feature.IS_IMAGE_PDF in features)
    }

    @Test
    fun `applies only to PDF objects`() {
        val enricher = PdfImageInvestigationRealizer(extractorOf(""))
        assertTrue(PdfImageInvestigation().accepts(ObjectState(ObjectKind.PDF)))
        assertFalse(PdfImageInvestigation().accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(PdfImageInvestigation().accepts(ObjectState(ObjectKind.TEXT)))
    }
}
