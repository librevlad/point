package com.point.executors

import com.point.core.flow.ObjectClassifier
import com.point.core.flow.OoxmlOfficeTextExtractor
import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.collectionOrder
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * У презентации есть слайды (#1105, решение владельца 23.08.2026).
 *
 * Живой прогон 17.08.2026 (A34, OBJ-040): .pptx из трёх слайдов предлагала только «Извлечь
 * текст», и та возвращала один сплошной текст всех слайдов подряд — сумма с первого слайда и
 * контакт со второго лежали общей кучей, и сказать, где что сказано, было нечем.
 *
 * Проверка идёт настоящим .pptx и путём человека: принёс файл → увидел дверь на первом
 * экране → нажал → получил набор, где каждый слайд знает своё.
 */
class SlidesActionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val classifier = ObjectClassifier()

    private val store = object : com.point.core.flow.ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File(tmp.newFolder(), "out.$extension").absolutePath)
        override suspend fun clear() = Unit
    }

    private val registry = DefaultCapabilityRegistry(
        capabilities = setOf(OfficeCapability(), SlidesCapability(), OpenCapability(), ShareCapability()),
        policy = DefaultBubblePolicy(),
    )

    private fun doorsFor(state: ObjectState) = registry.bubblesFor(state).map { it.capabilityId.value }.toSet()

    /** Настоящая .pptx — слайды лежат отдельными частями архива, как их кладёт PowerPoint. */
    private fun pptx(vararg slides: Pair<Int, String>): PointObject {
        val file = File(tmp.newFolder(), NAME)
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zos.write("<p:presentation/>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            slides.forEach { (number, text) ->
                zos.putNextEntry(ZipEntry("ppt/slides/slide$number.xml"))
                zos.write(slideXml(text).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return PointObject(
            id = "p",
            mime = PPTX,
            uri = ScratchRef(file.absolutePath),
            state = classifier.classify(PPTX, file.length(), file.name),
            metadata = mapOf("name" to file.name),
        )
    }

    private fun slideXml(text: String) =
        "<p:sld><p:cSld><p:spTree><a:t>$text</a:t></p:spTree></p:cSld></p:sld>"

    private suspend fun split(obj: PointObject) =
        SlidesRealizer(store, OoxmlOfficeTextExtractor()).perform(obj, null)

    private fun partsOf(result: ActionResult): Map<String, String> {
        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.COLLECTION, out.type)
        return File(out.uri.value).listFiles().orEmpty().associate { it.name to it.readText() }
    }

    /**
     * Дверь стоит на первом экране: из чего документ состоит, видно по имени и mime, и ждать
     * прочитанных байтов для этого не нужно.
     */
    @Test
    fun `у презентации есть «Слайды», а у текстового документа их нет`() {
        val presentation = classifier.classify(PPTX, SIZE, NAME)
        val document = classifier.classify(DOCX, SIZE, "акт.docx")

        assertTrue("человек не увидит, что презентация состоит из слайдов", "slides" in doorsFor(presentation))
        assertTrue("«Слайды» предложены документу, у которого их нет", "slides" !in doorsFor(document))
    }

    /**
     * То самое место, где прежде выходила общая куча: сумма названа на первом слайде, контакт
     * — на втором, и знание держится своего слайда.
     */
    @Test
    fun `презентация из трёх слайдов становится набором, где каждый слайд знает своё`() = runTest {
        val obj = pptx(
            1 to "Слайд первый — смета. Сумма 12 500 грн",
            2 to "Слайд второй — контакты. Олена Ковальчук",
            3 to "Слайд третий — итог",
        )

        val result = split(obj)

        assertEquals("3", (result as ActionResult.Success).result.metadata["count"])
        val parts = partsOf(result)
        val first = parts.getValue(SlidesRealizer.slideName(1))
        val second = parts.getValue(SlidesRealizer.slideName(2))

        assertTrue(first, "12 500" in first)
        assertTrue(second, "Ковальчук" in second)
        assertTrue("сумма первого слайда лежит и во втором", "12 500" !in second)
        assertTrue("контакт второго слайда лежит и в первом", "Ковальчук" !in first)
    }

    /**
     * Номер части — номер слайда в презентации, а не место в списке уцелевших: слайд без
     * текста в набор не идёт (войти в пустоту нечем), но следующий за ним не занимает его
     * номер. Порядок при этом — знание набора (#1207): по имени «Слайд 10» встал бы вторым.
     */
    @Test
    fun `слайд без текста не сдвигает номера, а десятый не встаёт между первым и вторым`() = runTest {
        val obj = pptx(1 to "первый", 2 to "", 3 to "третий", 10 to "десятый")

        val out = (split(obj) as ActionResult.Success).result

        assertEquals(
            listOf(1, 3, 10).map { SlidesRealizer.slideName(it) },
            collectionOrder(out.metadata),
        )
    }

    /**
     * Пустой набор успехом не считается: в презентации из одних картинок читать нечего, и
     * человеку это говорится про сам документ, а не про сорвавшуюся попытку.
     */
    @Test
    fun `презентация без текста говорит, что читать нечего, а не рождает пустой набор`() = runTest {
        val result = split(pptx(1 to "", 2 to ""))

        assertEquals(com.point.core.flow.NO_TEXT_IN_OFFICE, (result as ActionResult.Failure).reason)
        assertFalse("это про сам документ, а не про сорвавшуюся попытку", result.recoverable)
    }

    private companion object {

        const val NAME = "презентация.pptx"

        const val SIZE = 3_032L

        const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
