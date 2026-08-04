package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ящик приёма переживает пересоздание экрана (#114).
 *
 * Поворот телефона — это новый `onCreate`, и раньше он заводил НОВЫЙ ящик: ссылка, которую человек
 * уже показал соседу или отправил, молча умирала. Решение «продолжаем прежний или заводим новый»
 * вынуто отдельной функцией именно поэтому — это и есть место находки.
 */
class ReceiveBoxTest {

    @Test fun `сохранённый ящик продолжается, а не заводится заново`() {
        val box = restoredBox("aaaaaaaaaaaaaaaaaaaaaa", "https://relay/u/aaaaaaaaaaaaaaaaaaaaaa")
        assertEquals("aaaaaaaaaaaaaaaaaaaaaa", box?.id)
        assertEquals("https://relay/u/aaaaaaaaaaaaaaaaaaaaaa", box?.link)
    }

    @Test fun `без сохранённого ящика продолжать нечего`() {
        assertNull(restoredBox(null, null))
        // Половина сохранённого — не ящик: ссылка без адреса не даёт забрать файл, а адрес без
        // ссылки нечего показать человеку. Полуживой ящик хуже нового.
        assertNull(restoredBox("aaaaaaaaaaaaaaaaaaaaaa", null))
        assertNull(restoredBox(null, "https://relay/u/aaaaaaaaaaaaaaaaaaaaaa"))
        assertNull(restoredBox("", ""))
    }
}
