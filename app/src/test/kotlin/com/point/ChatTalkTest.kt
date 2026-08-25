package com.point

import com.point.core.flow.AiChatResponder
import com.point.core.flow.ObjectStore
import com.point.core.model.CapabilityId
import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Правила разговора проверяются напрямую (#833): они больше не заперты внутри модели
 * представления на 2480 строк.
 */
class ChatTalkTest {

    @get:Rule val temp = TemporaryFolder()

    private val obj = PointObject(
        "o1", "application/pdf", ScratchRef("/x.pdf"), ObjectState(ObjectKind.PDF),
    )

    /** Разговору от хранилища нужен только черновик под ответ. */
    private fun store() = object : ObjectStore {
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("chat-talk-", ".$extension").apply { deleteOnExit() }.absolutePath)

        override suspend fun ingest(sourceUri: String, mime: String) = error("разговор не принимает объектов")
        override suspend fun ingestMultiple(sources: List<String>) = error("разговор не принимает объектов")
        override suspend fun put(
            result: com.point.core.model.ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("разговор ничего не кладёт")
        override suspend fun children(collection: PointObject, limit: Int) =
            com.point.core.flow.CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int): String = ""
        override suspend fun clear() = Unit
    }

    /** Читатель документа, который помнит, сколько раз его позвали. */
    private class Pdf(private val text: String) : com.point.core.flow.PdfTextExtractor {
        var asked = 0
        override suspend fun extractText(obj: PointObject): String {
            asked++
            return text
        }
    }

    private val noPdf = Pdf("")

    /**
     * Знание объекта в разговоре — настоящее (#1138): тот же `GraphKnowledge`, которым
     * пользуется продукт, а не подменённый источник текста.
     */
    private fun talk(
        reply: String = "ответ модели",
        pdf: com.point.core.flow.PdfTextExtractor = noPdf,
        seen: (String?) -> Unit = {},
    ) = ChatTalk(
        responder = AiChatResponder { _, content, _, _ ->
            seen(content)
            reply
        },
        store = store(),
        knowledge = com.point.core.flow.GraphKnowledge(store(), pdf),
    )

    private val knowsPdf: (CapabilityId, ObjectState) -> String? =
        { id, _ -> if (id.value == "pdf") "Сделать PDF" else null }

    @Test
    fun `прежняя переписка о том же объекте продолжается, чужая — нет`() {
        val kept = ChatState(obj = obj, messages = listOf(ChatMessage(ChatRole.USER, "было")))
        val other = obj.copy(id = "o2")

        assertEquals(1, talk().opened(obj, kept).messages.size)
        assertEquals(0, talk().opened(other, kept).messages.size)
    }

    @Test
    fun `просьба сделать вещь приходит действием, а не объектом из ниоткуда`() = runTest {
        val said = talk().said(ChatState(obj = obj), "сделай pdf")

        val answered = talk().answered(said, "сделай pdf", said.messages, knowsPdf)

        assertEquals(CapabilityId("pdf"), answered.offer?.capabilityId)
        assertTrue("реплики модели быть не должно", answered.messages.none { it.role == ChatRole.ASSISTANT })
    }

    @Test
    fun `вопрос остаётся разговором`() = runTest {
        val said = talk().said(ChatState(obj = obj), "о чём это?")

        val answered = talk().answered(said, "о чём это?", said.messages, knowsPdf)

        assertNull(answered.offer)
        assertEquals(ChatRole.ASSISTANT, answered.messages.last().role)
    }

    /**
     * Разговор о документе идёт с документом в руках (#780).
     *
     * Экран был открыт над «3. План проведення перевірки.pdf», а модель отвечала «мне нужно
     * знать, о каком документе идёт речь». Документ лежал у Point и в вопрос не попадал.
     */
    @Test
    fun `содержимое документа уходит модели вместе с вопросом`() = runTest {
        val seen = mutableListOf<String?>()
        val talk = talk(pdf = Pdf("План проведення перевірки"), seen = { seen += it })
        val said = talk.said(ChatState(obj = obj), "главные тезисы")

        talk.answered(said, "главные тезисы", said.messages, knowsPdf)

        assertEquals(listOf("План проведення перевірки"), seen)
    }

    /**
     * Содержимое добывается один раз на разговор (#1241).
     *
     * Каждая реплика заново разбирала весь PDF: длинный документ — секунды мёртвого времени
     * до того, как вопрос вообще ушёл с телефона, и так на каждый ход.
     */
    @Test
    fun `документ разбирается один раз на разговор, а не на каждую реплику`() = runTest {
        val pdf = Pdf("Договор №42 от 2026 года")
        val seen = mutableListOf<String?>()
        val talk = talk(pdf = pdf, seen = { seen += it })

        val first = talk.answered(talk.said(ChatState(obj = obj), "о чём это?"), "о чём это?", emptyList(), knowsPdf)
        talk.answered(talk.said(first, "а сумма?"), "а сумма?", first.messages, knowsPdf)

        assertEquals("документ разобрали на каждую реплику", 1, pdf.asked)
        assertEquals(
            "вторая реплика ушла без документа",
            listOf("Договор №42 от 2026 года", "Договор №42 от 2026 года"),
            seen,
        )
    }

    /** Прочитанное раньше — то же знание объекта: файл второй раз не читается. */
    @Test
    fun `прочитанное с кадра уходит модели, а документ не разбирается`() = runTest {
        val sidecar = temp.newFile("ocr.txt").apply { writeText("текст со снимка") }
        val shot = obj.copy(metadata = mapOf(com.point.core.flow.META_OCR_TEXT_REF to sidecar.absolutePath))
        val pdf = Pdf("текстовый слой")
        val seen = mutableListOf<String?>()
        val talk = talk(pdf = pdf, seen = { seen += it })

        val said = talk.said(ChatState(obj = shot), "что тут?")
        talk.answered(said, "что тут?", said.messages, knowsPdf)

        assertEquals(listOf("текст со снимка"), seen)
        assertEquals("документ читали, хотя прочтение уже было", 0, pdf.asked)
    }

    @Test
    fun `действия для этого объекта нет — разговор отвечает словами`() = runTest {
        val said = talk().said(ChatState(obj = obj), "сделай pdf")

        val answered = talk().answered(said, "сделай pdf", said.messages) { _, _ -> null }

        assertNull("нельзя предлагать действие, которого нет", answered.offer)
        assertEquals(ChatRole.ASSISTANT, answered.messages.last().role)
    }

    @Test
    fun `прерванный ответ говорит об этом, а не молчит`() {
        val stopped = talk().stopped(ChatState(obj = obj, pending = true))

        assertEquals(false, stopped.pending)
        assertTrue(stopped.notice.orEmpty().isNotBlank())
    }

    @Test
    fun `забранный ответ — слово модели, и это записано в происхождении`() = runTest {
        val chat = ChatState(obj = obj, messages = listOf(ChatMessage(ChatRole.ASSISTANT, "суть")))

        val born = talk().answerObject(chat, "суть")

        assertEquals(Provenance.MODEL, born.provenance)
        assertEquals(listOf(obj.id), born.sourceObjects)
        assertEquals("суть", File(born.uri.value).readText())
    }
}
