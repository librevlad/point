package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTest {

    @Test
    fun `минутная голосовуха работает без предупреждений`() {
        assertEquals(LISTENING, listeningStage("audio/ogg", MINUTE_OF_OPUS))
    }

    @Test
    fun `трёхминутная — та самая, ради которой срез и сделан — говорит, что займёт время`() {
        val stage = listeningStage("audio/ogg", 3 * MINUTE_OF_OPUS)

        assertTrue(stage.startsWith(LISTENING))
        assertTrue("человек должен видеть, сколько примерно длится запись", "3 мин" in stage)
        assertTrue("займёт время" in stage)
    }

    @Test
    fun `один «средний» битрейт был бы враньём — wav считается своим`() {

        val threeMb = 3L * 1024 * 1024

        assertTrue(recordingMinutes("audio/ogg", threeMb)!! > 10.0)
        assertTrue(recordingMinutes("audio/wav", threeMb)!! < 1.0)
    }

    @Test
    fun `формат с неизвестным битрейтом не получает выдуманных минут`() {
        assertNull(recordingMinutes("audio/amr", 5L * 1024 * 1024))

        assertEquals(LISTENING, listeningStage("audio/amr", 5L * 1024 * 1024))
    }

    @Test
    fun `пустого или неизвестного веса хватает, чтобы промолчать, а не показать «0 мин»`() {
        assertNull(recordingMinutes("audio/ogg", 0L))
        assertEquals(LISTENING, listeningStage("audio/ogg", 0L))
    }

    @Test
    fun `имя файла работает, когда типа нет`() {
        assertTrue(recordingMinutes("application/octet-stream", 3 * MINUTE_OF_OPUS, "AUD-1.ogg")!! > LONG_MINUTES)
    }

    @Test
    fun `длина записи — та же строка, что человек раньше видел только после тапа`() {
        val length = recordingLength("audio/ogg", 3 * MINUTE_OF_OPUS)

        assertEquals("примерно 3 мин", length)

        assertEquals(
            "$LISTENING — $length, это займёт время",
            listeningStage("audio/ogg", 3 * MINUTE_OF_OPUS),
        )
    }

    @Test
    fun `сорок секунд и сорок минут — разные слова, и оба видны до тапа`() {

        assertEquals("примерно 40 сек", recordingLength("audio/ogg", opusSeconds(40)))
        assertEquals("примерно 40 мин", recordingLength("audio/ogg", opusSeconds(40 * 60)))
    }

    @Test
    fun `неизвестный битрейт не получает выдуманной длины и до тапа тоже`() {
        assertNull(recordingLength("audio/amr", 5L * 1024 * 1024))
        assertNull(recordingLength("audio/ogg", 0L))
    }

    @Test
    fun `огрызок в секунду молчит, а не обещает «примерно 1 сек»`() {
        assertNull(recordingLength("audio/ogg", 1024L))
    }

    @Test
    fun `почти минута названа минутой, а не «60 сек»`() {
        assertEquals("примерно 1 мин", recordingLength("audio/ogg", opusSeconds(59)))
    }

    private fun opusSeconds(seconds: Int) = seconds * 3000L

    private companion object {

        const val MINUTE_OF_OPUS = 180L * 1024
    }
}
