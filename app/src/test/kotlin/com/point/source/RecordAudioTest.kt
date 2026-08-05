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

    /** Момент записи. Ожидаемое время берётся у той же `stampLabel` — иначе тест судил бы часовой
     *  пояс машины, а не имя записи (сам формат пришит `AgoLabelTest`). */
    private val recordedAt = 1_754_325_912_345L

    @Test
    fun `записанный файл становится объектом`() {
        val produced = recordedToProduced(
            path = "/cache/record-1.m4a",
            mime = "audio/mp4",
            exists = { true },
            toUri = { "file://$it" },
            recordedAt = { recordedAt },
        )

        assertEquals("file:///cache/record-1.m4a", produced?.uri)
        assertEquals("audio/mp4", produced?.mime)
    }

    /**
     * #533: имя записи — «Запись, 4 авг 19:25», а не `record-1754325912345.m4a`.
     *
     * Живой замер: в «Недавнем» две записи подряд назывались одинаково-нечитаемо, и отличить
     * вчерашний разговор от сегодняшней диктовки можно было только по времени под строкой.
     */
    @Test
    fun `запись называется собой и временем, а не именем файла`() {
        val produced = recordedToProduced(
            path = "/cache/record-1754325912345.m4a",
            mime = "audio/mp4",
            exists = { true },
            toUri = { "file://$it" },
            recordedAt = { recordedAt },
        )

        assertEquals("Запись, " + com.point.core.flow.stampLabel(recordedAt), produced?.name)
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
