package com.point.core.flow

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
        override suspend fun extractText(obj: PointObject, atMost: Int?) = ""
    }

    @Test
    fun `стадия перевода называет выбранный язык`() = runTest {
        val src = File(tmp.root, "ru.txt").apply { writeText("Привет, как дела") }
        val out = File(tmp.root, "en.txt").apply { writeText("Hello, how are you") }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))

        val heard = stagesHeard { TranslateRealizer(llm(out), testKnowledge(noPdf())).perform(obj, null) }

        assertEquals(listOf("Перевожу на английский"), heard)
    }

    @Test
    fun `на PDF сначала слышно чтение текста — это отдельное ожидание до всякой сети`() = runTest {
        val out = File(tmp.root, "ru2.txt").apply { writeText("Привет") }
        val pdf = PointObject("id", "application/pdf", ScratchRef("/tmp/x.pdf"), ObjectState(ObjectKind.PDF))
        val extractor = object : PdfTextExtractor {
            override suspend fun extractText(obj: PointObject, atMost: Int?) = "Hello from a long PDF"
        }

        val heard = stagesHeard { TranslateRealizer(llm(out), testKnowledge(extractor)).perform(pdf, null) }

        assertEquals(listOf("Читаю текст PDF", "Перевожу на русский"), heard)
    }

    @Test
    fun `правка человека побеждает — стадия называет его язык, а не угаданный`() = runTest {
        val src = File(tmp.root, "ru3.txt").apply { writeText("Привет, как дела") }
        val out = File(tmp.root, "de.txt").apply { writeText("Hallo") }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))

        val heard = stagesHeard { TranslateRealizer(llm(out), testKnowledge(noPdf())).perform(obj, "немецкий") }

        assertEquals(listOf("Перевожу на немецкий"), heard)
    }

    @Test
    fun `пустому тексту стадии не положено — переводить нечего`() = runTest {
        val src = File(tmp.root, "empty.txt").apply { writeText("   ") }
        val out = File(tmp.root, "never.txt").apply { writeText("") }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))
        var result: ActionResult? = null

        val heard = stagesHeard { result = TranslateRealizer(llm(out), testKnowledge(noPdf())).perform(obj, null) }

        assertTrue(result is ActionResult.Failure)
        assertTrue(heard.isEmpty())
    }
}
