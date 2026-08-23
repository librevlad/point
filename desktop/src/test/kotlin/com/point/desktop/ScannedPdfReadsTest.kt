package com.point.desktop

import com.point.core.flow.InvestigationState
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.investigationStateOf
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Сканированный PDF читается одним действием (#1014): страницы → чтение → знание на самом
 * PDF. «Найти в документе» оживает ровно на тех документах, ради которых поиск делали.
 */
class ScannedPdfReadsTest {

    @get:Rule val temp = TemporaryFolder()

    private fun scannedPdf(pages: Int): PointObject {
        val file = temp.newFile("skan.pdf")
        org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            repeat(pages) { doc.addPage(org.apache.pdfbox.pdmodel.PDPage()) }
            doc.save(file)
        }
        return PointObject(
            "pdf",
            "application/pdf",
            ScratchRef(file.absolutePath),
            ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF)),
        )
    }

    @Test fun `текст страниц ложится знанием на сам PDF`() = runBlocking {
        val pagesRead = mutableListOf<String>()
        val realizer = PcReadDocumentRealizer(readPage = { page ->
            pagesRead += page.name
            "страница ${pagesRead.size}"
        })

        val result = realizer.perform(scannedPdf(pages = 2), null)

        assertTrue("родился объект вместо знания", result is ActionResult.Done)
        val found = (result as ActionResult.Done).findings!!
        assertTrue(Feature.HAS_TEXT in found.features)
        assertEquals(2, pagesRead.size)
        val text = File(found.metadata.getValue(META_OCR_TEXT_REF)).readText()
        assertTrue("текст первой страницы потерян", text.contains("1"))
        assertTrue("текст второй страницы потерян", text.contains("2"))
        assertEquals(
            InvestigationState.FOUND,
            investigationStateOf(found.metadata, KnownCapabilities.IMAGE_TEXT),
        )
    }

    @Test fun `прочитана часть страниц — вопрос не закрыт находкой целиком`() = runBlocking {
        var page = 0
        val realizer = PcReadDocumentRealizer(readPage = {
            page++
            if (page == 1) "только первая" else ""
        })

        val found = (realizer.perform(scannedPdf(pages = 2), null) as ActionResult.Done).findings!!

        assertEquals(
            InvestigationState.INSUFFICIENTLY_INVESTIGATED,
            investigationStateOf(found.metadata, KnownCapabilities.IMAGE_TEXT),
        )
    }

    @Test fun `ни одна страница не прочиталась — честный отказ, знание не тронуто`() = runBlocking {
        val realizer = PcReadDocumentRealizer(readPage = { "" })

        val result = realizer.perform(scannedPdf(pages = 1), null)

        assertTrue(result is ActionResult.Failure)
    }

    @Test fun `пометка сервиса «текста нет» на странице — страница не прочитана, отписка в текст не идёт`() = runBlocking {
        // Тот же сервис, что у снимка, на пустой странице отвечает не пустотой, а пометкой
        // (#1054): без сторожа она уходила в текст документа и шла в счёт прочитанных.
        var page = 0
        val realizer = PcReadDocumentRealizer(readPage = {
            page++
            if (page == 1) "только первая" else "*[No text detected]*"
        })

        val found = (realizer.perform(scannedPdf(pages = 2), null) as ActionResult.Done).findings!!

        val text = File(found.metadata.getValue(META_OCR_TEXT_REF)).readText()
        assertTrue("отписка ушла в текст документа", "No text detected" !in text)
        assertEquals(
            InvestigationState.INSUFFICIENTLY_INVESTIGATED,
            investigationStateOf(found.metadata, KnownCapabilities.IMAGE_TEXT),
        )
    }

    @Test fun `дверь открыта только скан-PDF без текста`() {
        val cap = PcReadDocumentCapability()

        assertTrue(cap.accepts(ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF))))
        assertTrue(!cap.accepts(ObjectState(ObjectKind.PDF)))
        assertTrue(!cap.accepts(ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF, Feature.HAS_TEXT))))
    }
}
