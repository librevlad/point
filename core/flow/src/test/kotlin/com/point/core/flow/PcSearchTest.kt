package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Что экран говорит про поиск компьютеров в сети (#458).
 *
 * Блок «Найдено в сети» рисовался, только когда что-то уже нашлось, — и секунды сканирования
 * выглядели как «ничего нет, вводите руками».
 */
class PcSearchTest {

    @Test
    fun `пока ищем — так и говорим`() {
        assertEquals("Ищу компьютеры в этой сети…", pcSearchLine(PcSearch.RUNNING, found = 0))
    }

    @Test
    fun `нашлось — говорит сам список, а не строка над ним`() {
        assertNull(pcSearchLine(PcSearch.RUNNING, found = 2))
        assertNull(pcSearchLine(PcSearch.DONE, found = 1))
    }

    @Test
    fun `искали и не нашли — это ответ, а не тишина`() {
        // Пульс без конца был бы вторым враньём поверх первого: в сети с изоляцией клиентов
        // mDNS не ответит никогда.
        assertEquals("В этой сети компьютеров не видно", pcSearchLine(PcSearch.DONE, found = 0))
    }

    @Test
    fun `до начала поиска говорить не о чем`() {
        assertNull(pcSearchLine(PcSearch.IDLE, found = 0))
    }
}
