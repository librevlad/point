package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceTextTest {

    @Test
    fun `адрес известен — он первой строкой, координаты второй`() {
        val text = placeText(50.4501, 30.5234, "вулиця Хрещатик, 1, Київ")
        assertEquals("вулиця Хрещатик, 1, Київ\n50.450100, 30.523400", text)
    }

    @Test
    fun `адреса нет — остаются координаты, и это не отказ`() {
        assertEquals("50.450100, 30.523400", placeText(50.4501, 30.5234, null))
    }

    @Test
    fun `координаты не теряют знак и дробную часть`() {
        val text = placeText(-33.8688, 151.2093, null)
        assertTrue(text, text.startsWith("-33.868800"))
    }
}
