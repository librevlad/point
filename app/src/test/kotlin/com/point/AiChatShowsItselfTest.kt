package com.point

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разговор про объект начинается сразу, а не после трети пустого экрана (#900).
 *
 * Подсказки стояли по центру: между шапкой и ними висела треть экрана пустоты, и это читалось
 * как незагрузившийся интерфейс. Разбор пяти моделей — 6–7 из 10, все ITERATE, и все назвали
 * эту пустоту первой бедой.
 */
class AiChatShowsItselfTest {

    private val screen = File("src/main/kotlin/com/point/AiChatScreen.kt").readText()

    @Test
    fun `подсказки начинаются сверху, а не посреди экрана`() {
        val block = screen.substringAfter("private fun ChatSuggestions(").substringBefore("private const val AI_ICON")

        assertTrue("подсказки снова по центру", !block.contains("Alignment.CenterVertically"))
        assertTrue("подсказки не прижаты к верху", block.contains("Alignment.Top"))
    }

    @Test
    fun `сказано, что можно написать своё, а не только выбрать готовое`() {
        val block = screen.substringAfter("private fun ChatSuggestions(").substringBefore("private const val AI_ICON")

        assertTrue("подсказки выглядят единственным выбором", block.contains("напишите своё"))
    }

    @Test
    fun `шапка помнит объект больше чем одной обрезанной строкой`() {
        val header = screen.substringAfter("text = \"Спросить AI\"").substringBefore("val listState")

        assertTrue("объект снова в одну строку", header.contains("maxLines = 2"))
    }
}
