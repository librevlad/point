package com.point.executors

import com.point.core.flow.LlmClient
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
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

class AiChatResponderImplTest {

    private val image = PointObject("i", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    private fun pdfSaying(text: String) = object : PdfTextExtractor {
        override suspend fun extractText(obj: PointObject) = text
    }

    private val noPdf = pdfSaying("")

    private fun answering(seen: (String) -> Unit): LlmClient {
        val answerFile = File.createTempFile("ai-reply", ".md").apply {
            writeText("ответ")
            deleteOnExit()
        }
        return object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                seen(prompt)
                return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(answerFile.path))
            }
        }
    }

    /**
     * Разговор о документе идёт с документом в руках (#780).
     *
     * Экран был открыт над «3. План проведення перевірки.pdf», а модель отвечала «мне нужно
     * знать, о каком документе идёт речь»: содержимое читалось только у текстового вида, и
     * PDF уходил в запрос пустым. Документ лежал у Point и в разговор не попадал.
     */
    @Test
    fun `PDF доходит до модели вместе с вопросом`() = runTest {
        val pdf = PointObject("d", "application/pdf", ScratchRef("/tmp/plan.pdf"), ObjectState(ObjectKind.PDF))
        var seenPrompt = ""

        AiChatResponderImpl(answering { seenPrompt = it }, pdfSaying("План проведення перевірки"))
            .reply(pdf, emptyList(), "главные тезисы")

        assertTrue("документ не дошёл до модели: $seenPrompt", "План проведення перевірки" in seenPrompt)
    }

    @Test
    fun `прочитанное раньше берётся, а файл второй раз не читается`() = runTest {
        val sidecar = File.createTempFile("ocr", ".txt").apply {
            writeText("текст со снимка")
            deleteOnExit()
        }
        val shot = image.copy(metadata = mapOf(com.point.core.flow.META_OCR_TEXT_REF to sidecar.path))
        var seenPrompt = ""

        AiChatResponderImpl(answering { seenPrompt = it }, noPdf).reply(shot, emptyList(), "что тут?")

        assertTrue("сидекар OCR не дошёл до модели", "текст со снимка" in seenPrompt)
    }

    @Test
    fun `reply feeds the model a history-aware prompt and returns its answer text`() = runTest {
        val answerFile = File.createTempFile("ai-reply", ".md").apply { writeText("Это чек на 693 грн.") }
        var seenPrompt = ""
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                seenPrompt = prompt
                return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(answerFile.path))
            }
        }
        val history = listOf(
            ChatMessage(ChatRole.USER, "что это?"),
            ChatMessage(ChatRole.ASSISTANT, "чек магазина"),
        )

        val reply = AiChatResponderImpl(llm, noPdf).reply(image, history, "на сколько?")

        assertEquals("Это чек на 693 грн.", reply)
        assertTrue("new message reaches the model", "на сколько?" in seenPrompt)
        assertTrue("history reaches the model", "чек магазина" in seenPrompt)
        answerFile.delete()
    }
}
