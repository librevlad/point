package com.point

import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingSubtitleTest {

    @Test
    fun `подпись называет прошедшее время, а не обещает будущее`() {
        val early = waitingSubtitle(elapsed = 3, network = true)

        assertTrue("должно быть про идущие секунды", early.contains("3 с"))
        assertTrue("обещаний времени быть не должно", !early.contains("займёт"))
    }

    // Прежде подпись объясняла ожидание словами «модель читает документ» — любому сетевому
    // действию. Живой прогон 11.08.2026: так объяснялась отправка файла на собственный
    // компьютер (#810). Чем занят Point, говорит стадия, а не догадка о работе.
    @Test
    fun `подпись не выдумывает работу, которой нет`() {
        val mid = waitingSubtitle(elapsed = 12, network = true)

        assertTrue(mid.contains("12 с"))
        assertTrue("про модель говорит тот, у кого модель работает", !mid.contains("модель"))
        assertTrue("и про документ — тоже", !mid.contains("документ"))
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

    @Test
    fun `долгое ожидание не выдаёт себя за длинную страницу`() {
        // Решение владельца (#690, #691): «долгая страница» — там, где страница
        // действительно длинная, а не там, где просто долго ждём сеть. Живой прогон
        // 2026-08-09: одна строка текста получила это же объяснение на 11.5 минуте.
        val long = waitingSubtitle(elapsed = 45, network = true)

        assertTrue("причина не проверена — Point не вправе её утверждать", !long.contains("страница"))
    }

    @Test
    fun `без возможности отменить ожидание остаётся честным, а не мелким шрифтом`() {
        val long = waitingSubtitle(elapsed = 45, network = true, cancelable = false)

        assertTrue(long.contains("45 с"))
        assertTrue(!long.contains("страница"))
        assertTrue("без кнопки отмены не обещаем её", !long.contains("отменить"))
    }
}
