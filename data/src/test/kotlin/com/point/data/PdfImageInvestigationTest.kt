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

    /**
     * Слой, который нельзя прочитать, текстовым слоем не является (#933, #995).
     *
     * У части бухгалтерских PDF внутри своя раскладка шрифта, и «извлечённый» текст — мусор.
     * Компьютер метит такой файл сканом при приёме; телефон считал его обычным документом, и
     * один и тот же файл на двух устройствах становился разным объектом с разными дверями.
     */
    @Test
    fun `подменённая раскладка шрифта — тот же признак, что и пустой слой`() = runTest {
        val features = PdfImageInvestigationRealizer(extractorOf(GARBLED)).look(pdf).features

        assertTrue("мусор из слоя снова сойдёт за текст", Feature.IS_IMAGE_PDF in features)
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

    private companion object {

        /** Слой украинского бухгалтерского PDF с подменённой раскладкой шрифта (#933). */
        const val GARBLED =
            "ToeapucrBo 3 o6MexeHop eignoeiganbHicrlo BaxraxoorpxMyBaq cKnaAaHHR " +
                "flocraqanbHHK e.qPnov Eniqgxtp 3aMoBHHK PaxyHok-cbakrypa"
    }
}
