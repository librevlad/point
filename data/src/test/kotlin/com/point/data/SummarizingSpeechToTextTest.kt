package com.point.data

import com.point.core.flow.LlmClient
import com.point.core.flow.NO_SUMMARY_MARKER
import com.point.core.flow.SUMMARIZE_PROMPT
import com.point.core.flow.SpeechToText
import com.point.core.flow.Transcription
import com.point.core.flow.modelReadableAudio
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SummarizingSpeechToTextTest {

    private val recording = PointObject(
        "id", "audio/ogg", ScratchRef("/scratch/voice.ogg"), ObjectState(ObjectKind.AUDIO),
        metadata = mapOf("name" to "PTT-20260804.ogg"),
    )

    private val longText = "Привет, слушай, перезвони мне до шести, и договор захвати, он на столе."

    private class FakeEngine(private val outcome: Transcription) : SpeechToText {
        override suspend fun transcribe(obj: PointObject) = outcome
    }

    private class FakeLlm(private val answer: String) : LlmClient {
        val asked = mutableListOf<Pair<PointObject, String>>()
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            asked += obj to prompt
            val md = File.createTempFile("answer", ".md").apply { writeText(answer); deleteOnExit() }
            return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(md.absolutePath))
        }
    }

    private class BrokenLlm : LlmClient {
        var calls = 0
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            calls++
            error("AI недоступен — нет подключения к интернету")
        }
    }

    @Test
    fun `суть добирается одним текстовым запросом по расшифровке`() = runTest {
        val llm = FakeLlm("Просят перезвонить до шести.")
        val engine = FakeEngine(Transcription.Heard(longText))

        val heard = SummarizingSpeechToText(engine, llm).transcribe(recording) as Transcription.Heard

        assertEquals("Просят перезвонить до шести.", heard.summary)
        assertEquals("расшифровка осталась дословной", longText, heard.text)
        val (obj, prompt) = llm.asked.single()
        assertTrue(prompt.startsWith(SUMMARIZE_PROMPT))
        assertTrue("модель спрашивают по самой расшифровке", prompt.contains(longText))

        assertEquals("text/plain", obj.mime)
        assertNull(modelReadableAudio(obj.mime, obj.metadata["name"]))
    }

    @Test
    fun `добираемую суть тоже просят по-русски — правило языка одно (#1036)`() = runTest {
        // Движок без сути (Whisper) добирает её отдельным вопросом; он просил «на языке
        // записи», и подзаголовок украинского голосового расходился с подзаголовком снимка.
        val llm = FakeLlm("Просят перезвонить до шести.")
        val engine = FakeEngine(Transcription.Heard("Передзвони мені до шостої, і договір захопи, він на столі."))

        SummarizingSpeechToText(engine, llm).transcribe(recording)

        val (_, prompt) = llm.asked.single()
        assertTrue("модель не просят о сути по-русски", prompt.contains("по-русски"))
    }

    @Test
    fun `модель не дала сути — расшифровка доезжает без неё, и это не ошибка`() = runTest {
        val engine = FakeEngine(Transcription.Heard(longText))

        val heard = SummarizingSpeechToText(engine, FakeLlm(NO_SUMMARY_MARKER))
            .transcribe(recording) as Transcription.Heard

        assertEquals("", heard.summary)
        assertEquals(longText, heard.text)
    }

    @Test
    fun `сбой модели не роняет уже добытые слова человека`() = runTest {
        val broken = BrokenLlm()
        val engine = FakeEngine(Transcription.Heard(longText))

        val heard = SummarizingSpeechToText(engine, broken).transcribe(recording) as Transcription.Heard

        assertEquals(1, broken.calls)
        assertEquals("", heard.summary)
        assertEquals(longText, heard.text)
    }

    @Test
    fun `тишина за сутью не ходит`() = runTest {
        val llm = FakeLlm("что-нибудь про запись, которой не слышали")

        val out = SummarizingSpeechToText(FakeEngine(Transcription.Silence), llm).transcribe(recording)

        assertEquals(Transcription.Silence, out)
        assertTrue("модель не тревожили", llm.asked.isEmpty())
    }

    @Test
    fun `движок, который сам назвал суть, проходит насквозь бесплатно`() = runTest {
        val llm = FakeLlm("вторая суть, которая никому не нужна")
        val engine = FakeEngine(Transcription.Heard(longText, "Просят перезвонить."))

        val heard = SummarizingSpeechToText(engine, llm).transcribe(recording) as Transcription.Heard

        assertEquals("Просят перезвонить.", heard.summary)
        assertTrue("второго запроса нет", llm.asked.isEmpty())
    }

    @Test
    fun `отказ движка остаётся отказом — сути тут не место`() = runTest {
        val engine = object : SpeechToText {
            override suspend fun transcribe(obj: PointObject): Transcription =
                error("Расшифровать некому — ни один движок распознавания речи не настроен")
        }
        val llm = FakeLlm("неважно")

        val e = runCatching { SummarizingSpeechToText(engine, llm).transcribe(recording) }.exceptionOrNull()

        assertTrue(e!!.message!!.contains("Расшифровать некому"))
        assertTrue(llm.asked.isEmpty())
    }
}
