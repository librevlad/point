package com.point.executors

import com.point.core.flow.LlmClient
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslateActionTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `russian text defaults to english`() {
        assertEquals("английский", translateDefaultTarget("Привет, как дела? Это тест."))
    }

    @Test
    fun `english text defaults to russian`() {
        assertEquals("русский", translateDefaultTarget("Hello, how are you? This is a test."))
    }

    @Test
    fun `mostly-latin mixed text defaults to russian`() {
        assertEquals("русский", translateDefaultTarget("Meeting at 10: agenda, notes, action items"))
    }

    private fun llm(out: File) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String) =
            ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(out.absolutePath))
    }

    private fun noPdf() = object : PdfTextExtractor {
        override suspend fun extractText(obj: PointObject) = ""
    }

    @Test
    fun `стадия перевода называет выбранный язык`() = runTest {
        val src = File(tmp.root, "ru.txt").apply { writeText("Привет, как дела") }
        val out = File(tmp.root, "en.txt").apply { writeText("Hello, how are you") }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))

        val heard = stagesHeard { TranslateRealizer(llm(out), noPdf()).perform(obj, null) }

        assertEquals(listOf("Перевожу на английский"), heard)
    }

    @Test
    fun `на PDF сначала слышно чтение текста — это отдельное ожидание до всякой сети`() = runTest {
        val out = File(tmp.root, "ru2.txt").apply { writeText("Привет") }
        val pdf = PointObject("id", "application/pdf", ScratchRef("/tmp/x.pdf"), ObjectState(ObjectKind.PDF))
        val extractor = object : PdfTextExtractor {
            override suspend fun extractText(obj: PointObject) = "Hello from a long PDF"
        }

        val heard = stagesHeard { TranslateRealizer(llm(out), extractor).perform(pdf, null) }

        assertEquals(listOf("Читаю текст PDF", "Перевожу на русский"), heard)
    }

    @Test
    fun `правка человека побеждает — стадия называет его язык, а не угаданный`() = runTest {
        val src = File(tmp.root, "ru3.txt").apply { writeText("Привет, как дела") }
        val out = File(tmp.root, "de.txt").apply { writeText("Hallo") }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))

        val heard = stagesHeard { TranslateRealizer(llm(out), noPdf()).perform(obj, "немецкий") }

        assertEquals(listOf("Перевожу на немецкий"), heard)
    }

    /**
     * Point прочитал чек, показал его текст на экране — и на «Перевести» отвечал «Нет текста
     * для перевода» (#1030): исполнитель брал текст по виду объекта, а не из графа, и делал
     * про объект утверждение, которое Point сам же только что опроверг.
     */
    @Test
    fun `переводится прочитанное с кадра, а не отказ «текста нет»`() = runTest {
        val read = File(tmp.root, "ocr.txt").apply { writeText("FAMILY DOLLAR\nMuskogee OK") }
        val out = File(tmp.root, "ru4.txt").apply { writeText("Семейный доллар") }
        val photo = PointObject(
            "img", "image/jpeg", ScratchRef("/tmp/chek.jpg"),
            ObjectState(ObjectKind.IMAGE, setOf(com.point.core.model.Feature.HAS_TEXT)),
            mapOf(com.point.core.flow.META_OCR_TEXT_REF to read.absolutePath),
        )
        var asked: String? = null
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                asked = prompt
                return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(out.absolutePath))
            }
        }

        val result = TranslateRealizer(llm, noPdf()).perform(photo, null)

        assertTrue("перевод не состоялся: $result", result is ActionResult.Success)
        assertTrue("в модель ушёл не прочитанный текст: $asked", asked!!.contains("FAMILY DOLLAR"))
    }

    @Test
    fun `пустому тексту стадии не положено — переводить нечего`() = runTest {
        val src = File(tmp.root, "empty.txt").apply { writeText("   ") }
        val out = File(tmp.root, "never.txt").apply { writeText("") }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))
        var result: ActionResult? = null

        val heard = stagesHeard { result = TranslateRealizer(llm(out), noPdf()).perform(obj, null) }

        assertTrue(result is ActionResult.Failure)
        assertTrue(heard.isEmpty())
    }
}
