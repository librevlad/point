package com.point

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDoorsTest {

    @Test fun `подпись перечисляет источники поимённо`() {
        assertEquals(
            "Буфер обмена · Голос · Камера",
            sourcesSubtitle(listOf("Буфер обмена", "Голос", "Камера")),
        )
    }

    @Test fun `без источников подписи нет вовсе`() {

        assertNull(sourcesSubtitle(emptyList()))
    }

    @Test fun `источник без имени не оставляет дырки в перечислении`() {
        assertEquals("Камера · Место", sourcesSubtitle(listOf("Камера", "  ", "Место")))
        assertNull(sourcesSubtitle(listOf("", " ")))
    }

    @Test fun `под именем стоит вид объекта и время`() {
        assertEquals("Изображение · 3 часа назад", historySubtitle("чек.png", "Изображение", "3 часа назад"))
        assertEquals("Текст · только что", historySubtitle(null, "Текст", "только что"))
    }

    @Test fun `вид не называется дважды, когда имя уже начинается с него`() {
        assertEquals("3 часа назад", historySubtitle("Запись, 4 авг 19:25", "Запись", "3 часа назад"))
        assertEquals("вчера", historySubtitle("Текст с чем-то", "Текст", "вчера"))
    }
}
