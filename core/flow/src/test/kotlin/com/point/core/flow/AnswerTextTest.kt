package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Преамбула модели не едет в объект (#501) — и ничего, кроме неё, не теряется.
 *
 * Случай владельца дословно: рецепт по фото продуктов начинался с «Вот вариант рецепта на основе
 * ваших ингредиентов:», и эта строка уезжала в сохранённый файл.
 */
class AnswerTextTest {

    @Test
    fun `обращение к человеку снимается, документ остаётся`() {
        val answer = """
            Вот вариант рецепта на основе ваших ингредиентов:

            **Запеченная рыба с овощами**

            **Ингредиенты:**
            - Филе рыбы
        """.trimIndent()

        val clean = withoutPreamble(answer)

        assertEquals(
            """
            **Запеченная рыба с овощами**

            **Ингредиенты:**
            - Филе рыбы
            """.trimIndent(),
            clean,
        )
    }

    /** Заголовок документа кончается двоеточием так же, но вводного слова в нём нет. */
    @Test
    fun `заголовок с двоеточием остаётся на месте`() {
        val answer = "Ингредиенты:\n- Филе рыбы\n- Цукини"
        assertEquals(answer, withoutPreamble(answer))

        val other = "Состав:\nвода, сахар"
        assertEquals(other, withoutPreamble(other))
    }

    @Test
    fun `строка без двоеточия — уже содержание`() {
        val answer = "Вот рецепт\n\nФиле рыбы запечь"
        assertEquals(answer, withoutPreamble(answer))
    }

    @Test
    fun `длинная первая строка не считается вежливостью`() {
        val long = "Вот " + "очень ".repeat(30) + "длинная строка:"
        val answer = "$long\n\nтело"
        assertEquals(answer, withoutPreamble(answer))
    }

    @Test
    fun `ответ из одной строки не трогаем`() {
        assertEquals("Вот ответ:", withoutPreamble("Вот ответ:"))
    }

    @Test
    fun `под преамбулой должно что-то остаться`() {
        val answer = "Вот результат:\n\n   "
        assertEquals(answer, withoutPreamble(answer))
    }

    @Test
    fun `английская вежливость снимается тоже`() {
        assertEquals("body", withoutPreamble("Here is the result:\n\nbody"))
        assertEquals("body", withoutPreamble("Sure, here it is:\n\nbody"))
    }

    @Test
    fun `жирная преамбула тоже снимается`() {
        assertEquals("тело", withoutPreamble("**Вот что получилось:**\n\nтело"))
    }
}
