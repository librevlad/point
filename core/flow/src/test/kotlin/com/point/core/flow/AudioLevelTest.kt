package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило «слушать до отправки» (#1053).
 *
 * Двадцать секунд цифровой тишины уезжали в Whisper и возвращались фразой «Thank you.» —
 * выдумкой, которая ложилась знанием об объекте. Решать, есть ли в записи звук, сервису
 * незачем: это видно по самому громкому сэмплу.
 */
class AudioLevelTest {

    @Test
    fun `цифровая тишина слышна и без облака`() {
        assertTrue(nothingToHear(0.0))
    }

    @Test
    fun `шум кодека вокруг нуля — тоже не речь`() {
        assertTrue(nothingToHear(AUDIBLE_PEAK / 5))
    }

    @Test
    fun `далёкий тихий голос остаётся речью и едет расшифровываться`() {
        assertFalse(nothingToHear(AUDIBLE_PEAK * 4))
    }

    @Test
    fun `громкая запись вопросов не вызывает`() {
        assertFalse(nothingToHear(1.0))
    }

    /** Не измерили — не «тихо»: незнакомый формат не отнимает у человека расшифровку. */
    @Test
    fun `неизмеренный уровень тишиной не считается`() {
        assertFalse(nothingToHear(null))
    }

    /** Порог низкий намеренно: ошибиться в сторону лишнего похода в сервис дешевле. */
    @Test
    fun `порог слышимости остаётся у самого нуля`() {
        assertTrue(AUDIBLE_PEAK < 0.01)
    }

    /**
     * Декодер мог не отдать ни одного сэмпла — незнакомый формат, отказ, конец потока сразу.
     * Прежде такой разбор отдавал ноль, и несостоявшееся измерение выдавалось за тишину:
     * вопрос закрывался «не нашлось» по тому, чего не слушали (ADR-0001 §9).
     */
    @Test
    fun `ни одного сэмпла не разобрали — это не тишина`() {
        assertFalse(nothingToHear(measuredPeak(0.0, anySampleHeard = false)))
    }

    @Test
    fun `разобранный ноль тишиной и остаётся`() {
        assertTrue(nothingToHear(measuredPeak(0.0, anySampleHeard = true)))
    }
}
