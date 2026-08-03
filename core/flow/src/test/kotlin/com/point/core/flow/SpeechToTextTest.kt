package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Договор расшифровки (#223) — чистая половина: разбор ответа модели и то, как расшифровка
 * выглядит объектом.
 *
 * Судится здесь ровно одно: **что Point считает услышанным**. Формат ответа строгий не из
 * любви к формату — по маркеру видно, ответила модель по делу или ушла в прозу, и «выдуманная
 * суть» отличается от настоящей только тем, что её никто не говорил.
 */
class SpeechToTextTest {

    @Test
    fun `суть и дословный текст приходят одним ответом`() {
        val heard = parseTranscription(
            """
            СУТЬ: Просят перезвонить до шести и взять договор.
            РАСШИФРОВКА:
            Привет, слушай, перезвони мне до шести, и договор захвати.
            """.trimIndent(),
        )

        assertTrue(heard is Transcription.Heard)
        heard as Transcription.Heard
        assertEquals("Просят перезвонить до шести и взять договор.", heard.summary)
        assertEquals("Привет, слушай, перезвони мне до шести, и договор захвати.", heard.text)
    }

    @Test
    fun `ответ без маркеров не теряет слова человека, но и не выдумывает суть`() {
        // Модель, не удержавшая формат, всё равно принесла расшифровку. Терять её из-за
        // двоеточия хуже, чем остаться без сводки; а сводку выдумать нельзя вовсе.
        val heard = parseTranscription("Привет, перезвони мне до шести.") as Transcription.Heard

        assertEquals("Привет, перезвони мне до шести.", heard.text)
        assertEquals("", heard.summary)
    }

    @Test
    fun `одна суть без расшифровки — не удача`() {
        // Пересказ без слов человека — это не то, за чем шли: расшифровки не досталось.
        assertEquals(
            Transcription.Silence,
            parseTranscription("СУТЬ: Про встречу.\nРАСШИФРОВКА:\n   "),
        )
    }

    @Test
    fun `тишина — это ответ, а не сбой`() {
        assertEquals(Transcription.Silence, parseTranscription(NO_SPEECH_MARKER))
        assertEquals(Transcription.Silence, parseTranscription("   "))
    }

    @Test
    fun `«я не получил записи» — не тишина, а отказ`() {
        // Две разные новости человеку: в записи нечего расшифровывать против «файл не доехал».
        // Склейка соврала бы ровно в одном из двух случаев — и незаметно.
        val e = runCatching { parseTranscription(NO_AUDIO_MARKER) }.exceptionOrNull()

        assertTrue(e is IllegalStateException)
        assertTrue(e!!.message!!.contains("не получила запись"))
    }

    @Test
    fun `в промпте стоят оба маркера — иначе строгий разбор ловил бы то, о чём не просили`() {
        assertTrue(NO_AUDIO_MARKER in TRANSCRIBE_PROMPT)
        assertTrue(NO_SPEECH_MARKER in TRANSCRIBE_PROMPT)
        assertTrue("СУТЬ:" in TRANSCRIBE_PROMPT && "РАСШИФРОВКА:" in TRANSCRIBE_PROMPT)
    }

    // --- Как расшифровка выглядит объектом ---

    @Test
    fun `суть стоит над расшифровкой — ради неё и не слушают три минуты`() {
        val md = transcriptMarkdown(Transcription.Heard("Дословный текст", "Коротко о главном"))

        assertTrue(md.indexOf("## Суть") < md.indexOf("## Расшифровка"))
        assertTrue("Коротко о главном" in md)
        assertTrue("Дословный текст" in md)
    }

    @Test
    fun `без сути нет и заголовка сути`() {
        // Пустой раздел «Суть» обещал бы то, чего в объекте нет.
        val md = transcriptMarkdown(Transcription.Heard("Дословный текст"))

        assertTrue("## Суть" !in md)
        assertTrue("## Расшифровка" in md)
    }

    // --- Что модель действительно прочтёт ---

    @Test
    fun `одно голосовое под разными именами типа едет под одним каноническим`() {
        assertEquals("audio/ogg", modelReadableAudio("audio/ogg"))
        assertEquals("audio/ogg", modelReadableAudio("audio/opus"))
        assertEquals("audio/ogg", modelReadableAudio("application/ogg"))
        assertEquals("audio/mp4", modelReadableAudio("audio/x-m4a"))
        assertEquals("audio/mpeg", modelReadableAudio("audio/mp3"))
        assertEquals("audio/wav", modelReadableAudio("AUDIO/WAV; charset=binary"))
    }

    @Test
    fun `запись без типа узнаётся по имени файла`() {
        assertEquals("audio/ogg", modelReadableAudio("application/octet-stream", "AUD-0001.OGG"))
        assertEquals("audio/mp4", modelReadableAudio("application/octet-stream", "voice.m4a"))
    }

    @Test
    fun `формат, которого модель не читает, называется несчитаемым — а не отправляется наугад`() {
        // amr и wma — настоящие форматы диктофонов, и отправить их значит получить HTTP 400
        // и показать человеку кусок чужого JSON вместо слов.
        assertNull(modelReadableAudio("audio/amr", "заметка.amr"))
        assertNull(modelReadableAudio("audio/x-ms-wma", "запись.wma"))
        assertNull(modelReadableAudio("image/png", "фото.png"))
    }
}
