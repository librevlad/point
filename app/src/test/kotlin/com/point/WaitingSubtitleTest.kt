package com.point

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Экран ожидания говорит правду о времени (#288).
 *
 * Было: «Это займёт несколько секунд» и чек-лист, который двигало настенное время — через 12
 * секунд он упирался в последний шаг и застывал. А «В Excel» — это две модели по фото, минута
 * и больше: обещание нарушалось каждый раз и читалось как «зависло».
 */
class WaitingSubtitleTest {

    @Test
    fun `подпись называет прошедшее время, а не обещает будущее`() {
        val early = waitingSubtitle(elapsed = 3, network = true)

        assertTrue("должно быть про идущие секунды", early.contains("3 с"))
        assertTrue("обещаний времени быть не должно", !early.contains("займёт"))
    }

    @Test
    fun `на длинной работе подпись объясняет, что происходит`() {
        val mid = waitingSubtitle(elapsed = 12, network = true)

        assertTrue(mid.contains("12 с"))
        assertTrue("человек должен понимать, чего ждёт", mid.contains("модель"))
    }

    @Test
    fun `после полуминуты подпись напоминает про отмену`() {
        val long = waitingSubtitle(elapsed = 45, network = true)

        assertTrue(long.contains("45 с"))
        assertTrue("на минутной работе отмена — не мелкий шрифт", long.contains("отменить"))
    }

    @Test
    fun `локальная работа не притворяется облачной`() {
        val local = waitingSubtitle(elapsed = 1, network = false)

        assertTrue(!local.contains("модель"))
    }
}
