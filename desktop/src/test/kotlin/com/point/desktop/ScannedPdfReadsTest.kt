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

    /**
     * Страницы прочитаны, текста на них нет — это знание, а не сбой (#1254/#1255).
     *
     * «Текста нет» говорится только про прочитанную страницу. Прежде здесь стоял отказ
     * «Не разобрал текст ни на одной странице» — утверждение о документе, которым отвечали и
     * на пустой документ, и на молчащий сервис.
     */
    @Test fun `страницы пусты — это ответ «текста нет», а не сбой чтения`() = runBlocking {
        val realizer = PcReadDocumentRealizer(readPage = { "" })

        val result = realizer.perform(scannedPdf(pages = 1), null)

        assertTrue("прочитанный пустой документ — не отказ: $result", result is ActionResult.Done)
        val done = result as ActionResult.Done
        assertEquals(com.point.core.flow.NO_TEXT_IN_DOCUMENT, done.message)
        assertEquals(
            InvestigationState.NOT_FOUND,
            investigationStateOf(done.findings!!.metadata, KnownCapabilities.IMAGE_TEXT),
        )
    }

    /**
     * Сервис не ответил ни на одной странице — человек слышит про сервис (#1255).
     *
     * Прежде `getOrDefault("")` превращал 401 и таймаут в пустую страницу, и документ получал
     * приговор «Не разобрал текст ни на одной странице»: причина не доезжала вовсе, а человек
     * шёл переснимать документ вместо того, чтобы поправить ключ.
     */
    @Test fun `сервис отказал на всех страницах — сказано про сервис, а не про документ`() = runBlocking {
        val realizer = PcReadDocumentRealizer(readPage = {
            com.point.core.flow.ownWords(com.point.core.flow.serviceRefusal(401))
        })

        val result = realizer.perform(scannedPdf(pages = 2), null)

        val said = (result as ActionResult.Failure).reason
        assertEquals(com.point.core.flow.KEY_NOT_TAKEN, said)
        assertTrue("сорвавшееся чтение выдано за отсутствие текста: $said", "текст" !in said.lowercase())
    }

    /**
     * Часть страниц сервис не взял — и человек об этом слышит (#1255). Без этого «1 из 2»
     * читается как «на второй текста нет», хотя её никто не прочёл.
     */
    @Test fun `часть страниц сорвалась — счёт назван вместе с причиной`() = runBlocking {
        var page = 0
        val realizer = PcReadDocumentRealizer(readPage = {
            page++
            if (page == 1) "только первая" else com.point.core.flow.ownWords(com.point.core.flow.serviceRefusal(429))
        })

        val done = realizer.perform(scannedPdf(pages = 2), null) as ActionResult.Done

        // Слова — из общего правила: телефон на том же исходе говорит ровно это же (#1254).
        assertEquals(
            com.point.core.flow.pagesRead(
                total = 2,
                readable = 1,
                broken = 1,
                brokenSaid = com.point.core.flow.serviceRefusal(429),
            ).said,
            done.message,
        )
        assertTrue(
            "причина срыва до человека не доехала: " + done.message,
            com.point.core.flow.looksLikeQuotaFailure(done.message),
        )
        assertEquals(
            InvestigationState.INSUFFICIENTLY_INVESTIGATED,
            investigationStateOf(done.findings!!.metadata, KnownCapabilities.IMAGE_TEXT),
        )
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
