package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerTextTest {

    @Test
    fun `обращение к человеку снимается, документ остаётся`() {
        val answer = """
            Вот вариант рецепта на основе ваших ингредиентов:

            **Запеченная рыба с овощами**

            **Ингредиенты:**
            - Филе рыбы
        """.trimIndent()

        val clean = answerOnly(answer)

        assertEquals(
            """
            **Запеченная рыба с овощами**

            **Ингредиенты:**
            - Филе рыбы
            """.trimIndent(),
            clean,
        )
    }

    @Test
    fun `заголовок с двоеточием остаётся на месте`() {
        val answer = "Ингредиенты:\n- Филе рыбы\n- Цукини"
        assertEquals(answer, answerOnly(answer))

        val other = "Состав:\nвода, сахар"
        assertEquals(other, answerOnly(other))
    }

    @Test
    fun `строка без двоеточия — уже содержание`() {
        val answer = "Вот рецепт\n\nФиле рыбы запечь"
        assertEquals(answer, answerOnly(answer))
    }

    @Test
    fun `длинная первая строка не считается вежливостью`() {
        val long = "Вот " + "очень ".repeat(30) + "длинная строка:"
        val answer = "$long\n\nтело"
        assertEquals(answer, answerOnly(answer))
    }

    @Test
    fun `ответ из одной строки не трогаем`() {
        assertEquals("Вот ответ:", answerOnly("Вот ответ:"))
    }

    @Test
    fun `под преамбулой должно что-то остаться`() {
        val answer = "Вот результат:\n\n   "
        assertEquals(answer.trim(), answerOnly(answer))
    }

    @Test
    fun `английская вежливость снимается тоже`() {
        assertEquals("body", answerOnly("Here is the result:\n\nbody"))
        assertEquals("body", answerOnly("Sure, here it is:\n\nbody"))
    }

    @Test
    fun `жирная преамбула тоже снимается`() {
        assertEquals("тело", answerOnly("**Вот что получилось:**\n\nтело"))
    }

    @Test
    fun `ход мысли вслух до человека не доходит — остаётся ответ`() {
        val table = "Товар\tЦіна\nГречка\t42"
        val answer = "<think>Okay, the user wants me to extract data from a document image.\n" +
            "**1. Analyze the Document Structure**\n</think>\n\n" + table

        assertEquals(table, answerOnly(answer))
    }

    @Test
    fun `рассуждение, оборвавшееся на полуслове, ответом не становится`() {
        val cut = "<think>Okay, the user wants me to extract data. Гречка 2 шт, Всього 33 095,69"

        val said = runCatching { answerOnly(cut) }.exceptionOrNull()?.message

        assertEquals(ONLY_REASONING, said)
    }

    @Test
    fun `сервис снял открывающий тег — рассуждение снимается всё равно`() {
        val table = "Item\tPrice\nTea\t42"

        assertEquals(table, answerOnly("The user wants a table.</think>\n\n" + table))
    }

    @Test
    fun `рассуждение посреди ответа уносит только себя`() {
        val head = "Рахунок №7"
        val tail = "Гречка — 2 кг"

        assertEquals(head + "\n\n" + tail, answerOnly(head + "\n<thinking>a total?</thinking>\n" + tail))
    }

    @Test
    fun `обычный ответ не трогается`() {
        val answer = "Рахунок №7\n\nГречка — 2 кг"

        assertEquals(answer, answerOnly(answer))
    }
}
