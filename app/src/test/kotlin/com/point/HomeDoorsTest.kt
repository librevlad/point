package com.point

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDoorsTest {

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
