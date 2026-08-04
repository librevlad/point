package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Звукозапись самим Point (#246).
 *
 * Причина среза — не вкусовая: на телефоне владельца (Samsung A34) на системное намерение
 * «записать звук» не отвечает ни одно приложение, и прежний источник честно прятался по
 * `isAvailable`. Здесь проверяется чистая часть: что становится объектом, а что — нет.
 */
class RecordAudioTest {

    @Test
    fun `записанный файл становится объектом`() {
        val produced = recordedToProduced(
            path = "/cache/record-1.m4a",
            mime = "audio/mp4",
            exists = { true },
            toUri = { "file://$it" },
        )

        assertEquals("file:///cache/record-1.m4a", produced?.uri)
        assertEquals("audio/mp4", produced?.mime)
    }

    @Test
    fun `отменённая запись — ничего, а не пустой объект`() {
        assertNull(recordedToProduced(null, null, exists = { true }, toUri = { it }))
        assertNull(recordedToProduced("", "audio/mp4", exists = { true }, toUri = { it }))
    }

    /** Битый или нулевой файл объектом не становится: пустая карточка хуже честной тишины. */
    @Test
    fun `файла нет или он пуст — объекта нет`() {
        assertNull(recordedToProduced("/cache/gone.m4a", "audio/mp4", exists = { false }, toUri = { it }))
    }

    /** Тип не назвали — берём свой, а не «application/octet-stream»: расшифровка читает по типу. */
    @Test
    fun `без типа запись остаётся звуком`() {
        val produced = recordedToProduced("/cache/r.m4a", null, exists = { true }, toUri = { it })
        assertEquals(RecordAudioActivity.MIME, produced?.mime)
    }

    @Test
    fun `часы записи считают минуты и секунды`() {
        assertEquals("0:00", recordClock(0))
        assertEquals("0:07", recordClock(7))
        assertEquals("1:42", recordClock(102))
        assertEquals("10:00", recordClock(600))
    }
}
