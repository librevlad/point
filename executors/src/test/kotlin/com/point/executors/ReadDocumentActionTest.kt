package com.point.executors

import com.point.core.flow.InvestigationState
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.TextRecognizer
import com.point.core.flow.investigationStateOf
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Прочитать документ» на телефоне — та же работа, что на компьютере (#1254).
 *
 * У телефонной половины не было ни одного теста, и копии успели разойтись словами о сбое:
 * телефон отдавал человеку `it.message` — английский хвост библиотеки на битом PDF, — а
 * компьютер глушил любую причину одной фразой. Здесь проверяется путь человека: что он
 * услышит и в каком состоянии останется вопрос чтения. Зеркало этих проверок — на компьютере
 * (`ScannedPdfReadsTest`), и обе стороны обязаны отвечать одинаково.
 */
class ReadDocumentActionTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject, from: PointObject?, by: CapabilityId?) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    /** Растеризатор рисует страницы файлами — дальше их читает движок, как на устройстве. */
    private fun rasterizer(pages: Int): PdfRasterizer {
        val dir = File.createTempFile("pages-", "").apply { delete(); mkdirs(); deleteOnExit() }
        repeat(pages) { index -> File(dir, "page-$index.png").apply { writeBytes(ByteArray(4)); deleteOnExit() } }
        return object : PdfRasterizer {
            override suspend fun rasterize(obj: PointObject) = ScratchRef(dir.absolutePath)
            override suspend fun rasterizeFirstPage(obj: PointObject) = null
        }
    }

    private fun reads(vararg answers: () -> String): TextRecognizer {
        var page = 0
        return object : TextRecognizer {
            override suspend fun recognize(obj: PointObject): String = answers[page++ % answers.size]()
        }
    }

    private val document = PointObject(
        "pdf",
        "application/pdf",
        ScratchRef("/tmp/skan.pdf"),
        ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF)),
    )

    private suspend fun read(pages: Int, vararg answers: () -> String): ActionResult =
        ReadDocumentRealizer(rasterizer(pages), reads(*answers), store).perform(document, null)

    @Test fun `текст страниц ложится знанием на сам PDF`() = runTest {
        val done = read(2, { "первая" }, { "вторая" }) as ActionResult.Done

        val found = done.findings!!
        assertTrue("текст не стал знанием документа", Feature.HAS_TEXT in found.features)
        assertEquals(
            InvestigationState.FOUND,
            investigationStateOf(found.metadata, KnownCapabilities.IMAGE_TEXT),
        )
        val text = File(found.metadata.getValue(META_OCR_TEXT_REF)).readText()
        assertTrue("страницы потеряны: $text", text.contains("первая") && text.contains("вторая"))
    }

    /** Страницы прочитаны, текста нет — это ответ на вопрос, а не сбой операции. */
    @Test fun `страницы пусты — это ответ «текста нет», а не сбой чтения`() = runTest {
        val result = read(2, { "" }, { "" })

        assertTrue("прочитанный пустой документ — не отказ: $result", result is ActionResult.Done)
        val done = result as ActionResult.Done
        assertEquals(com.point.core.flow.NO_TEXT_IN_DOCUMENT, done.message)
        assertEquals(
            InvestigationState.NOT_FOUND,
            investigationStateOf(done.findings!!.metadata, KnownCapabilities.IMAGE_TEXT),
        )
    }

    /**
     * Движок не завёлся ни на одной странице — человек слышит про попытку, а не приговор
     * документу, и вопрос чтения остаётся нетронутым (#1254): сорвавшееся исследование
     * никогда не переводит знание в «не нашлось».
     */
    @Test fun `движок сорвался на всех страницах — сказано про попытку, а не про документ`() = runTest {
        val result = read(2, { error("engine init failed") })

        val said = (result as ActionResult.Failure).reason
        assertEquals(com.point.core.flow.READ_NOT_NOW, said)
        assertTrue("чужой текст движка у человека: $said", "engine" !in said)
    }

    /** Часть страниц сорвалась — счёт назван вместе с причиной, теми же словами, что на ПК. */
    @Test fun `часть страниц сорвалась — счёт назван вместе с причиной`() = runTest {
        val done = read(2, { "только первая" }, { error("engine init failed") }) as ActionResult.Done

        assertEquals(
            com.point.core.flow.pagesRead(
                total = 2,
                readable = 1,
                broken = 1,
                brokenSaid = com.point.core.flow.READ_NOT_NOW,
            ).said,
            done.message,
        )
        assertEquals(
            InvestigationState.INSUFFICIENTLY_INVESTIGATED,
            investigationStateOf(done.findings!!.metadata, KnownCapabilities.IMAGE_TEXT),
        )
    }

    /** Битый PDF: разбор сорвался целиком — своими словами про PDF, а не хвостом библиотеки. */
    @Test fun `битый документ объясняется своими словами, а не текстом библиотеки`() = runTest {
        val broken = object : PdfRasterizer {
            override suspend fun rasterize(obj: PointObject): ScratchRef = error("PDF header not found")
            override suspend fun rasterizeFirstPage(obj: PointObject) = null
        }

        val result = ReadDocumentRealizer(broken, reads({ "" }), store).perform(document, null)

        val said = (result as ActionResult.Failure).reason
        assertTrue("английский хвост библиотеки на экране: $said", "PDF header" !in said)
        assertEquals(com.point.core.flow.readerFailure("PDF header not found", ObjectKind.PDF), said)
    }

    @Test fun `имя работы у обоих устройств одно — из общего словаря`() {
        assertEquals(KnownCapabilities.READ_DOCUMENT, ReadDocumentCapability().id)
    }
}
