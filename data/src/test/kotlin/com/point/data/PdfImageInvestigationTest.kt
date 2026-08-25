package com.point.data

import com.point.core.flow.CollectionContent
import com.point.core.flow.GraphKnowledge
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfImageInvestigationTest {

    /** Читатель, который считает разборы: цена вопроса видна по тому, сколько их было. */
    private class Pdf(private val layer: String) : PdfTextExtractor {
        var read = 0

        override suspend fun extractText(obj: PointObject): String {
            read++
            return layer
        }
    }

    private class ScratchStore : ObjectStore {
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("pdf-layer", ".$extension").apply { deleteOnExit() }.absolutePath)

        override suspend fun ingest(sourceUri: String, mime: String) = throw UnsupportedOperationException()
        override suspend fun ingestMultiple(sources: List<String>) = throw UnsupportedOperationException()
        override suspend fun put(
            result: ResultObject,
            from: PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = throw UnsupportedOperationException()
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int) =
            runCatching { File(obj.uri.value).takeIf(File::isFile)?.readText()?.take(limit) }.getOrNull().orEmpty()
        override suspend fun clear() = Unit
    }

    private fun extractorOf(text: String) = Pdf(text)

    /** Содержимое документа-образца: сверяется с ним, а не с формулировкой экрана. */
    private val layer = "Договор №42 от 2026 года"

    private fun realizer(pdf: PdfTextExtractor) = PdfImageInvestigationRealizer(pdf, ScratchStore())

    private val pdf = PointObject(
        id = "id",
        mime = "application/pdf",
        uri = ScratchRef("/scratch/x.pdf"),
        state = ObjectState(ObjectKind.PDF),
    )

    @Test
    fun `flags IS_IMAGE_PDF when the PDF has no text layer`() = runTest {
        val features = realizer(extractorOf("   \n  \t ")).look(pdf).features
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
        val found = realizer(extractorOf(GARBLED)).look(pdf)

        assertTrue("мусор из слоя снова сойдёт за текст", Feature.IS_IMAGE_PDF in found.features)
        assertFalse("мусор лёг знанием документа", META_OCR_TEXT_REF in found.metadata)
    }

    @Test
    fun `no flag when the PDF has extractable text`() = runTest {
        val features = realizer(extractorOf(layer)).look(pdf).features
        assertFalse(Feature.IS_IMAGE_PDF in features)
    }

    /**
     * Слой есть — он и есть знание объекта (#1241): достаётся один раз и ложится в Graph
     * тем же ключом и тем же признаком, каким ложится любое прочтение (#995).
     */
    @Test
    fun `текстовый слой ложится знанием объекта`() = runTest {
        val found = realizer(extractorOf(layer)).look(pdf)

        val ref = found.metadata[META_OCR_TEXT_REF]
        assertNotNull("слой не стал знанием", ref)
        assertEquals(layer, File(ref!!).readText())
        assertTrue("прочитанное не названо прочитанным", Feature.HAS_TEXT in found.features)
        assertFalse("документ со слоем не скан", Feature.IS_IMAGE_PDF in found.features)
    }

    /**
     * Исследование объявляет то, что приносит (#1241).
     *
     * `mayYield` — не украшение: по нему `DefaultEnrichment` решает, стоит ли вообще запускать
     * дорогое исследование. Умолчи одну из двух находок — и вопрос судился бы по одной двери
     * из двух, а вторая для гейта не существовала бы вовсе.
     */
    @Test
    fun `объявлено то, что исследование приносит`() = runTest {
        val declared = PdfImageInvestigation().meta.mayYield

        val scan = realizer(extractorOf("")).look(pdf).features
        val withLayer = realizer(extractorOf(layer)).look(pdf).features

        assertTrue("находка «слоя нет» не объявлена", declared.containsAll(scan))
        assertTrue("находка «слой есть» не объявлена", declared.containsAll(withLayer))
        assertEquals("объявлено то, чего исследование не приносит", scan + withLayer, declared)
    }

    /**
     * Второй вопрос о том же документе не стоит второго разбора (#1241): текущее знание
     * находит уже добытый слой, и разговор, перевод и «В Word» получают его даром.
     */
    @Test
    fun `второй вопрос о документе не разбирает его заново`() = runTest {
        val reader = extractorOf(layer)
        val understood = pdf.copy(metadata = realizer(reader).look(pdf).metadata)
        val read = reader.read

        val text = GraphKnowledge(ScratchStore(), reader).textOf(understood)

        assertEquals(layer, text)
        assertEquals("документ разобрали ещё раз", read, reader.read)
    }

    /**
     * У известного скана слоя нет, и спрашивать его заново нечем (#1241): прежде каждый
     * вопрос заново гонял все страницы ради заведомой пустоты.
     */
    @Test
    fun `у известного скана текст не добывается заново`() = runTest {
        val reader = extractorOf("")
        val scan = pdf.copy(state = ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF)))

        val text = GraphKnowledge(ScratchStore(), reader).textOf(scan)

        assertEquals(null, text)
        assertEquals("скан разобрали ради известной пустоты", 0, reader.read)
    }

    @Test
    fun `applies only to PDF objects`() {
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
