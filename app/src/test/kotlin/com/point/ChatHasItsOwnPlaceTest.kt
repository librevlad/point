package com.point

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разговор живёт своим держателем, а не внутри общего (#833).
 *
 * `FlowViewModel` был 2480 строк и 36 зависимостей: приём объекта, действия, обогащение,
 * фокус, разговор, ключи, аккаунт, связка с компьютером. Правка любого экрана трогала один
 * файл, и две параллельные карточки конфликтовали в нём же.
 *
 * Решение владельца: «по одной теме за раз, начать с разговора».
 */
class ChatHasItsOwnPlaceTest {

    private val dir = File("src/main/kotlin/com/point")

    @Test
    fun `у разговора свой держатель`() {
        assertTrue("держателя разговора нет", File(dir, "ChatFlow.kt").isFile)
    }

    @Test
    fun `общий не знает про внутренности разговора`() {
        val flow = File(dir, "FlowViewModel.kt").readText()

        assertTrue("работа разговора снова в общем файле", !flow.contains("chatJob"))
        assertTrue("отмена вопроса снова в общем файле", !flow.contains("talk.stopped("))
        assertTrue("сборка реплики снова в общем файле", !flow.contains("talk.said("))
    }

    @Test
    fun `двери разговора остались на месте — экран зовёт их так же`() {
        val flow = File(dir, "FlowViewModel.kt").readText()

        listOf("fun closeChat(", "fun sendChatMessage(", "fun runChatOffer(",
               "fun cancelChatMessage(", "fun takeChatAnswer(").forEach {
            assertTrue("экран потерял способ позвать разговор: $it", flow.contains(it))
        }
    }
}
