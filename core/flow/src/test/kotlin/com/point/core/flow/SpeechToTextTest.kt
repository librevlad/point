package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

        val heard = parseTranscription("Привет, перезвони мне до шести.") as Transcription.Heard

        assertEquals("Привет, перезвони мне до шести.", heard.text)
        assertEquals("", heard.summary)
    }

    @Test
    fun `одна суть без расшифровки — не удача`() {

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

    @Test
    fun `суть добирается по расшифровке — и остаётся сутью, а не пересказом целиком`() {
        val text = "Привет, слушай, перезвони мне до шести, и договор захвати, он на столе лежит."

        assertEquals("Просят перезвонить до шести.", parseSummary("Просят перезвонить до шести.", text))

        assertEquals("", parseSummary(text, text))
    }

    @Test
    fun `суть не выдумывается, когда её не дали`() {
        val text = "Привет, слушай, перезвони мне до шести, и договор захвати."

        assertEquals("", parseSummary(NO_SUMMARY_MARKER, text))
        assertEquals("", parseSummary("   ", text))

        assertEquals("", parseSummary("Просят перезвонить.", "Перезвони."))
    }

    @Test
    fun `в промпте сути стоит слово отказа — иначе «не смог» пришло бы прозой`() {
        assertTrue(NO_SUMMARY_MARKER in SUMMARIZE_PROMPT)
    }

    @Test
    fun `суть стоит над расшифровкой — ради неё и не слушают три минуты`() {
        val md = transcriptMarkdown(Transcription.Heard("Дословный текст", "Коротко о главном"))

        assertTrue(md.indexOf("## Суть") < md.indexOf("## Расшифровка"))
        assertTrue("Коротко о главном" in md)
        assertTrue("Дословный текст" in md)
    }

    @Test
    fun `без сути нет и заголовка сути`() {

        val md = transcriptMarkdown(Transcription.Heard("Дословный текст"))

        assertTrue("## Суть" !in md)
        assertTrue("## Расшифровка" in md)
    }

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

        assertNull(modelReadableAudio("audio/amr", "заметка.amr"))
        assertNull(modelReadableAudio("audio/x-ms-wma", "запись.wma"))
        assertNull(modelReadableAudio("image/png", "фото.png"))
    }
}
