package com.point.data

import com.point.core.flow.Transcription
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Whisper на Groq (#223) поверх поддельной сети — ни ключа, ни байта наружу.
 *
 * Судится то, что человек почувствует: запрос уходит на ручку расшифровки и несёт имя (без него
 * сервис отвечает 403 — замер 04.08.2026); язык **не форсируется**, потому что записи бывают и на
 * украинском, и на русском; пустой ответ становится тишиной, а не пустой расшифровкой; отказ
 * приходит словами.
 */
class GroqWhisperSpeechToTextTest {

    private fun recording(mime: String, bytes: Long = 2048, name: String? = null): PointObject {
        // Длина ставится, а не пишется: тест про предел размера иначе выливал бы на диск 25 МБ.
        val file = File.createTempFile("voice", ".bin").apply { deleteOnExit() }
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return PointObject(
            "id", mime, ScratchRef(file.absolutePath), ObjectState(ObjectKind.AUDIO),
            metadata = name?.let { mapOf("name" to it) } ?: emptyMap(),
        )
    }

    private fun whisper(http: FakeHttpFiles, key: String = "gsk-free") = GroqWhisperSpeechToText(
        http, key, "https://api.groq.com/openai/v1", "whisper-large-v3-turbo",
    )

    private fun heard(text: String) = HttpResult(200, """{"text":"$text"}""")

    @Test
    fun `запрос уходит на ручку расшифровки — с ключом, с именем и без форсированного языка`() = runTest {
        val http = FakeHttpFiles(onPost = { heard("А може до якого ґазди?") })

        whisper(http).transcribe(recording("audio/ogg", name = "PTT-20260804.opus"))

        val sent = http.posts.single()
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", sent.url)
        assertEquals("Bearer gsk-free", sent.headers["Authorization"])
        // Без User-Agent Groq отвечает 403 — из-за этого провайдер числился мёртвым.
        assertTrue(sent.headers["User-Agent"].orEmpty().startsWith("Point/"))
        assertEquals("whisper-large-v3-turbo", sent.field("model"))
        assertEquals("json", sent.field("response_format"))
        // Форсированный язык превратил бы украинскую речь в русскую транслитерацию — то есть в
        // уверенно неправильный текст. У владельца записи на обоих языках.
        assertNull("язык не форсируется", sent.field("language"))
        // Имя синтетическое: сервису нужно расширение, а не имя из чужого мессенджера.
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

        assertTrue(e!!.message!!.contains("Этот формат записи модель не читает"))
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

        val e = runCatching { whisper(http, key = "").transcribe(recording("audio/ogg")) }.exceptionOrNull()

        assertTrue(e!!.message!!.contains("Whisper не настроен"))
        assertTrue(http.posts.isEmpty())
    }

    @Test
    fun `кончившийся лимит доходит словами — это повод идти дальше, а не авария`() = runTest {
        val http = FakeHttpFiles(onPost = { HttpResult(429, """{"error":{"message":"rate limit"}}""") })

        val e = runCatching { whisper(http).transcribe(recording("audio/ogg")) }.exceptionOrNull()

        assertTrue(e!!.message!!.contains("слишком часто"))
    }

    @Test
    fun `отказ ключа называет и вторую причину — запрос без имени`() = runTest {
        // 403 у Groq означает не только «ключ не тот»: так же отвечает запрос без User-Agent.
        val http = FakeHttpFiles(onPost = { HttpResult(403, "forbidden") })

        val e = runCatching { whisper(http).transcribe(recording("audio/ogg")) }.exceptionOrNull()

        assertTrue(e!!.message!!.contains("без имени"))
    }
}
