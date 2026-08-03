package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Скажи, что будет долго» (#223).
 *
 * Проверяется не арифметика, а обещание продукту: над длинной записью экран не молчит, а над
 * форматом с неизвестным битрейтом не выдумывает минут.
 */
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
        // 3 МБ opus — это два часа; 3 МБ wav — восемнадцать секунд. Разница в сто раз,
        // и именно поэтому битрейт берётся у формата, а не усредняется.
        val threeMb = 3L * 1024 * 1024

        assertTrue(recordingMinutes("audio/ogg", threeMb)!! > 10.0)
        assertTrue(recordingMinutes("audio/wav", threeMb)!! < 1.0)
    }

    @Test
    fun `формат с неизвестным битрейтом не получает выдуманных минут`() {
        assertNull(recordingMinutes("audio/amr", 5L * 1024 * 1024))
        // И тогда фразы о минутах человек не видит вовсе — только само действие.
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

    private companion object {
        /** Голосовое на минуту в opus 24 кбит/с ≈ 180 КБ. */
        const val MINUTE_OF_OPUS = 180L * 1024
    }
}
