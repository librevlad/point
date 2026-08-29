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
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import kotlin.random.Random

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

    /**
     * Страница документа укладывается в предел сервиса сама (#1255).
     *
     * Одиночный снимок Point ужимал (`ImageFit`), а страницу отдавал как есть. Рисует эту
     * страницу сам Point, и скан такого размера почти всегда тяжелее предела: человек получал
     * «Файл великоват — сервис его не берёт» на картинку, которую не выбирал и уменьшить не
     * может, — документ оставался непрочитанным целиком, и сделать с этим было нечего.
     *
     * Сцепка здесь та же, что в `Main.kt`: страницы рисует чтение документа, читает их
     * облачный читатель снимка. Сервис в тесте ведёт себя как настоящий — тяжелее предела
     * отказывает, как отказывает OCR.space.
     */
    @Test fun `страница тяжелее предела ужимается, а не получает отказ`() = runBlocking {
        val reader = PcCloudOcrRealizer({ OcrConfig(url = serveLikeOcrSpace()) })
        val realizer = PcReadDocumentRealizer(readPage = { page -> reader.readFrame(page, "image/png") })

        val result = realizer.perform(scannedPhoto(), null)

        assertTrue("документ не прочитан: $result", result is ActionResult.Done)

        // Уменьшенная копия уходит как jpeg: `image/png` означал бы, что страница ушла как есть.
        assertEquals("страница ушла к сервису неужатой", "image/jpeg", sentMime)
        assertTrue(
            "к сервису ушло байт: $sentBytes при пределе " + com.point.core.flow.OcrSpaceTalk.MAX_BYTES,
            sentBytes in 1..com.point.core.flow.OcrSpaceTalk.MAX_BYTES,
        )
    }

    @Test fun `дверь открыта только скан-PDF без текста`() {
        val cap = PcReadDocumentCapability()

        assertTrue(cap.accepts(ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF))))
        assertTrue(!cap.accepts(ObjectState(ObjectKind.PDF)))
        assertTrue(!cap.accepts(ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF, Feature.HAS_TEXT))))
    }

    private var server: HttpServer? = null

    private var sentMime = ""

    private var sentBytes = 0L

    @After fun stopServer() {
        server?.stop(0)
    }

    /** Сервис отвечает как OCR.space: тяжелее предела — отказ внутри успешного ответа (#1259). */
    private fun serveLikeOcrSpace(): String {
        val s = HttpServer.create(InetSocketAddress(0), 0)
        s.createContext("/parse") { exchange ->
            val form = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                .split('&')
                .associate { pair ->
                    val (name, value) = pair.split('=', limit = 2)
                    name to URLDecoder.decode(value, "UTF-8")
                }
            val image = form.getValue("base64Image")
            sentMime = image.substringAfter("data:").substringBefore(";base64,")
            sentBytes = Base64.getDecoder().decode(image.substringAfter(";base64,")).size.toLong()
            val answer = if (sentBytes > com.point.core.flow.OcrSpaceTalk.MAX_BYTES) {
                """{"IsErroredOnProcessing":true,"ErrorMessage":["File size exceeds the maximum limit"]}"""
            } else {
                """{"IsErroredOnProcessing":false,"ParsedResults":[{"ParsedText":"накладная 4512"}]}"""
            }
            val body = answer.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/parse"
    }

    /**
     * Страница, какой её отдаёт сканер: шум по всему листу. Пустой лист сжимается почти в
     * ничто и предела сервиса не касается — на таком документе дефекта не видно вовсе.
     */
    private fun scannedPhoto(): PointObject {
        val file = temp.newFile("skan-foto.pdf")
        val noise = BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB)
        val random = Random(17)
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                noise.setRGB(x, y, random.nextInt(0xFFFFFF))
            }
        }
        org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            val page = org.apache.pdfbox.pdmodel.PDPage(
                org.apache.pdfbox.pdmodel.common.PDRectangle(POINTS, POINTS),
            )
            doc.addPage(page)
            val drawn = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, noise)
            org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use {
                it.drawImage(drawn, 0f, 0f, POINTS, POINTS)
            }
            doc.save(file)
        }
        return PointObject(
            "pdf",
            "application/pdf",
            ScratchRef(file.absolutePath),
            ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF)),
        )
    }

    private companion object {

        /** Сторона листа в пунктах и в пикселях после отрисовки — те же 200 DPI, что у Point. */
        const val POINTS = 324f

        const val SIDE = 900
    }
}
