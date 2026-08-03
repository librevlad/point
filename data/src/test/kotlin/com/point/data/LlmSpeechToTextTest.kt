package com.point.data

import com.point.core.flow.LlmClient
import com.point.core.flow.NO_SPEECH_MARKER
import com.point.core.flow.TRANSCRIBE_PROMPT
import com.point.core.flow.Transcription
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
import java.io.RandomAccessFile

/**
 * Облачная расшифровка (#223) поверх фейкового [LlmClient].
 *
 * Судится то, что человек почувствует: формат, которого никто не читает, отказывает **до сети**;
 * ответ модели становится расшифровкой; сбой провайдера доходит словами, а не пустотой.
 */
class LlmSpeechToTextTest {

    private fun recording(mime: String, bytes: Long = 1024, name: String? = null): PointObject {
        // Длина ставится, а не пишется: тест про предел размера иначе выливал бы на диск 15 МБ.
        val file = File.createTempFile("voice", ".bin").apply { deleteOnExit() }
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return PointObject(
            "id", mime, ScratchRef(file.absolutePath), ObjectState(ObjectKind.AUDIO),
            metadata = name?.let { mapOf("name" to it) } ?: emptyMap(),
        )
    }

    /** Модель, отвечающая заданным текстом; заодно запоминает, о чём её попросили. */
    private class FakeLlm(private val answer: String) : LlmClient {
        var prompt: String? = null
        var calls = 0
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            this.prompt = prompt
            calls++
            val md = File.createTempFile("answer", ".md").apply { writeText(answer); deleteOnExit() }
            return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(md.absolutePath))
        }
    }

    @Test
    fun `ответ модели становится расшифровкой с сутью`() = runTest {
        val llm = FakeLlm("СУТЬ: Просят перезвонить.\nРАСШИФРОВКА:\nПерезвони мне до шести.")

        val heard = LlmSpeechToText(llm).transcribe(recording("audio/ogg")) as Transcription.Heard

        assertEquals("Просят перезвонить.", heard.summary)
        assertEquals("Перезвони мне до шести.", heard.text)
        assertEquals("движок спрашивает ровно по общему договору", TRANSCRIBE_PROMPT, llm.prompt)
    }

    @Test
    fun `тишина доезжает тишиной, а не пустой расшифровкой`() = runTest {
        val heard = LlmSpeechToText(FakeLlm(NO_SPEECH_MARKER)).transcribe(recording("audio/ogg"))

        assertEquals(Transcription.Silence, heard)
    }

    @Test
    fun `нечитаемый формат отказывает до сети — и до траты квоты`() = runTest {
        val llm = FakeLlm("неважно")

        val e = runCatching { LlmSpeechToText(llm).transcribe(recording("audio/amr", name = "заметка.amr")) }
            .exceptionOrNull()

        assertTrue(e!!.message!!.contains("Этот формат записи модель не читает"))
        assertEquals("провайдера не тревожили", 0, llm.calls)
    }

    @Test
    fun `слишком тяжёлая запись отказывает словами, а не молчаливым «без вложения»`() = runTest {
        // Без этой проверки файл сверх предела просто не прикладывался бы, и модель уверенно
        // рассказала бы про запись, которой не слышала.
        val llm = FakeLlm("неважно")

        val e = runCatching { LlmSpeechToText(llm).transcribe(recording("audio/ogg", bytes = MAX_INLINE_BYTES + 1)) }
            .exceptionOrNull()

        assertTrue(e!!.message!!.contains("слишком большая"))
        assertEquals(0, llm.calls)
    }

    @Test
    fun `сбой провайдера доходит до человека, а не глотается`() = runTest {
        val broken = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject =
                error("AI недоступен — нет подключения к интернету")
        }

        val e = runCatching { LlmSpeechToText(broken).transcribe(recording("audio/ogg")) }.exceptionOrNull()

        assertEquals("AI недоступен — нет подключения к интернету", e!!.message)
    }
}
