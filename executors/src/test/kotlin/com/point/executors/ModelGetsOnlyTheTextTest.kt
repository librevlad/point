package com.point.executors

import com.point.core.flow.JobReplyRealizer
import com.point.core.flow.ShoppingListRealizer

import com.point.core.flow.TranslateRealizer

import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Наружу уходит не больше, чем требуется (#1244).
 *
 * «Перевести», «Список покупок» и «Отклик» кладут прочитанный текст прямо в запрос, а
 * вместе с ним отправляли исходный объект: снимок кодировался и уезжал заново, PDF уезжал
 * целиком — ради задачи, которой нужны только символы. Заодно цепочка отбрасывала на снимке
 * и на PDF все текстовые бесплатные модели, которые справились бы мгновенно.
 */
class ModelGetsOnlyTheTextTest {

    @get:Rule val tmp = TemporaryFolder()

    private class Asked : LlmClient {
        var obj: PointObject? = null
        lateinit var answer: File
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            this.obj = obj
            return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(answer.absolutePath))
        }
    }

    private fun asked(): Asked = Asked().apply {
        answer = File(tmp.root, "answer.md").apply { writeText("ответ модели") }
    }

    /** Прочитанный снимок: текст лежит в графе сидекаром, сам кадр — картинка. */
    private fun readImage(text: String): PointObject {
        val sidecar = File(tmp.root, "reading.txt").apply { writeText(text) }
        val frame = File(tmp.root, "frame.jpg").apply { writeText("не важно, что за байты") }
        return PointObject(
            "id", "image/jpeg", ScratchRef(frame.absolutePath),
            ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(META_OCR_TEXT_REF to sidecar.absolutePath),
        )
    }

    private fun readPdf(text: String): PointObject {
        val sidecar = File(tmp.root, "pdf-reading.txt").apply { writeText(text) }
        val pdf = File(tmp.root, "doc.pdf").apply { writeText("%PDF-1.4") }
        return PointObject(
            "id", "application/pdf", ScratchRef(pdf.absolutePath),
            ObjectState(ObjectKind.PDF),
            metadata = mapOf(META_OCR_TEXT_REF to sidecar.absolutePath),
        )
    }

    @Test
    fun `перевод прочитанного снимка уходит текстом — кадр модели не нужен`() = runTest {
        val llm = asked()

        TranslateRealizer(llm, testKnowledge()).perform(readImage("Hello, world"), null)

        assertEquals("text/plain", llm.obj!!.mime)
        assertNull("ссылка на слой чтения уехала наружу", llm.obj!!.metadata[META_OCR_TEXT_REF])
    }

    @Test
    fun `перевод PDF уходит текстом — документ целиком не уезжает`() = runTest {
        val llm = asked()

        TranslateRealizer(llm, testKnowledge()).perform(readPdf("Договор аренды"), null)

        assertEquals("text/plain", llm.obj!!.mime)
    }

    @Test
    fun `список покупок по снимку рецепта уходит текстом`() = runTest {
        val llm = asked()

        ShoppingListRealizer(llm).perform(readImage("Борщ - свёкла 2 шт"), null)

        assertEquals("text/plain", llm.obj!!.mime)
    }

    @Test
    fun `отклик по объявлению в PDF уходит текстом`() = runTest {
        val llm = asked()

        JobReplyRealizer(llm).perform(readPdf("Требуется инженер"), "")

        assertEquals("text/plain", llm.obj!!.mime)
    }

    @Test
    fun `текстовый объект уходит как есть — подменять нечего`() = runTest {
        val llm = asked()
        val text = File(tmp.root, "note.md").apply { writeText("Привет, как дела") }
        val obj = PointObject(
            "id", "text/markdown", ScratchRef(text.absolutePath), ObjectState(ObjectKind.TEXT),
        )

        TranslateRealizer(llm, testKnowledge()).perform(obj, null)

        assertEquals("text/markdown", llm.obj!!.mime)
    }
}
