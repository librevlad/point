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

    /**
     * Время ушло в заголовок секции (#880): строка говорит сначала «что это», потом имя —
     * человек ищет глазами «голосовое», а не идентификатор файла.
     */
    @Test fun `строка говорит сначала вид, потом имя`() {
        assertEquals("Изображение · чек.png", rowSubtitle("чек.png", "Изображение"))

        // Имени нет вовсе — остаётся вид объекта.
        assertEquals("Текст", rowSubtitle(null, "Текст"))
    }

    @Test fun `вид не называется дважды, когда имя уже начинается с него`() {
        assertEquals("Запись, 4 авг 19:25", rowSubtitle("Запись, 4 авг 19:25", "Запись"))
        assertEquals("Текст с чем-то", rowSubtitle("Текст с чем-то", "Текст"))
    }
}
