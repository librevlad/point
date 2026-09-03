package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class GroqWhisperSpeechToTextTest {

    private fun recording(mime: String, bytes: Long = 2048, name: String? = null): PointObject {

        val file = File.createTempFile("voice", ".bin").apply { deleteOnExit() }
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return PointObject(
            "id", mime, ScratchRef(file.absolutePath), ObjectState(ObjectKind.AUDIO),
            metadata = name?.let { mapOf("name" to it) } ?: emptyMap(),
        )
    }

    private fun whisper(http: FakeHttpFiles, key: () -> String = { "gsk-free" }) =
        GroqWhisperSpeechToText(http, key, "https://api.groq.com/openai/v1", "whisper-large-v3-turbo")

    private fun heard(text: String) = HttpResult(200, """{"text":"$text"}""")

    @Test
    fun `запрос уходит на ручку расшифровки — с ключом и без форсированного языка`() = runTest {
        val http = FakeHttpFiles(onPost = { heard("А може до якого ґазди?") })

        whisper(http).transcribe(recording("audio/ogg", name = "PTT-20260804.opus"))

        val sent = http.posts.single()
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", sent.url)
        assertEquals("Bearer gsk-free", sent.headers["Authorization"])

        assertEquals("whisper-large-v3-turbo", sent.field("model"))
        assertEquals("json", sent.field("response_format"))

        assertNull("язык не форсируется", sent.field("language"))

        assertEquals("voice.ogg", sent.file("file")?.fileName)
        assertEquals("audio/ogg", sent.file("file")?.contentType)
    }

    @Test
    fun `ответ становится расшифровкой, а суть не выдумывается`() = runTest {
        val http = FakeHttpFiles(onPost = { heard("Перезвони мне до шести.") })

        val out = whisper(http).transcribe(recording("audio/ogg")) as Transcription.Heard

        assertEquals("Перезвони мне до шести.", out.text)
        assertEquals("Whisper сути не даёт, и придумывать её нечем", "", out.summary)
    }

    @Test
    fun `пустой ответ — это тишина, а не пустая расшифровка`() = runTest {
        val http = FakeHttpFiles(onPost = { heard("   ") })

        assertEquals(Transcription.Silence, whisper(http).transcribe(recording("audio/ogg")))
    }

    @Test
    fun `нечитаемый формат отказывает до сети — и до траты лимита`() = runTest {
        val http = FakeHttpFiles(onPost = { heard("неважно") })

        val e = runCatching { whisper(http).transcribe(recording("audio/amr", name = "заметка.amr")) }
            .exceptionOrNull()

        assertTrue(e!!.message!!.contains("Этот формат записи не читается"))
        assertTrue("сервис не тревожили", http.posts.isEmpty())
    }

    @Test
    fun `слишком тяжёлая запись отказывает словами, а не обрезанной отправкой`() = runTest {
        val http = FakeHttpFiles(onPost = { heard("неважно") })

        val e = runCatching {
            whisper(http).transcribe(recording("audio/ogg", bytes = 26L * 1024 * 1024))
        }.exceptionOrNull()

        assertTrue(e!!.message!!.contains("слишком большая"))
        assertTrue(http.posts.isEmpty())
    }

    @Test
    fun `нет ключа — честный отказ, а не запрос без ключа`() = runTest {
        val http = FakeHttpFiles(onPost = { heard("неважно") })

        val e = runCatching { whisper(http, key = { "" }).transcribe(recording("audio/ogg")) }.exceptionOrNull()

        val said = e!!.message!!
        assertTrue(said.contains("Whisper не настроен"))
        assertTrue("названо, ЧЕЙ ключ нужен: $said", said.contains("ключ Groq"))
        assertTrue("сказано, куда идти: $said", said.contains(KEY_SETTINGS_CALL))
        assertTrue(http.posts.isEmpty())
    }

    @Test
    fun `ключ спрашивается на каждый запрос — введённый минуту назад работает сразу`() = runTest {

        var key = ""
        val whisper = whisper(FakeHttpFiles(onPost = { heard("текст") }), key = { key })

        assertEquals(SpeechKeyNeed("Whisper слушает по ключу Groq", GROQ_PROVIDER_ID), whisper.missingKey())

        key = "gsk-от-человека"

        assertNull("движок включился без пересборки графа", whisper.missingKey())
    }

    @Test
    fun `в запрос уходит тот ключ, что задан сейчас, а не тот, что был при сборке`() = runTest {
        var key = "gsk-старый"
        val http = FakeHttpFiles(onPost = { heard("текст") })
        val whisper = whisper(http, key = { key })

        whisper.transcribe(recording("audio/ogg"))
        key = "gsk-новый"
        whisper.transcribe(recording("audio/ogg"))

        assertEquals("Bearer gsk-старый", http.posts.first().headers["Authorization"])
        assertEquals("Bearer gsk-новый", http.posts.last().headers["Authorization"])
    }

    @Test
    fun `кончившийся лимит доходит словами — это повод идти дальше, а не авария`() = runTest {
        val http = FakeHttpFiles(onPost = { HttpResult(429, """{"error":{"message":"rate limit"}}""") })

        val e = runCatching { whisper(http).transcribe(recording("audio/ogg")) }.exceptionOrNull()

        val said = e!!.message!!
        assertTrue(said, com.point.core.flow.looksLikeQuotaFailure(said))
        assertFalse("код протокола человеку ни о чём не говорит: $said", said.contains("429"))
    }

    @Test
    fun `не пустили — сказано про ключ и куда идти, без кода протокола`() = runTest {

        val http = FakeHttpFiles(onPost = { HttpResult(403, "forbidden") })

        val e = runCatching { whisper(http).transcribe(recording("audio/ogg")) }.exceptionOrNull()

        val said = e!!.message!!
        assertFalse("код протокола человеку ни о чём не говорит: $said", said.contains("403"))
        assertTrue("сказано, куда идти: $said", said.contains(com.point.core.flow.KEY_SETTINGS_CALL))
    }
}
