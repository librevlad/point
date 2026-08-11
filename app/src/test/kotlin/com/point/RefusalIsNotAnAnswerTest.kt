package com.point

import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Отказ операции не становится объектом (#793, решение владельца 11.08.2026: «отказ — не
 * ответ»).
 *
 * Живой прогон 11.08.2026: сеть выключена посреди разговора, вопрос не прошёл, в чате честное
 * «Не получилось ответить: Модель недоступна — нет подключения к интернету». Кнопка «Забрать
 * ответ» под этим осталась и родила объект «Текст · Ответ AI» с текстом ошибки внутри — его
 * можно было понять, перевести и отправить на компьютер.
 */
class RefusalIsNotAnAnswerTest {

    private val obj = PointObject(
        id = "o",
        mime = "application/pdf",
        uri = ScratchRef("/scratch/zayavka.pdf"),
        state = ObjectState(ObjectKind.PDF),
    )

    private fun chat(vararg messages: ChatMessage) = ChatState(obj = obj, messages = messages.toList())

    @Test
    fun `после неудачи забирать нечего`() {
        val state = chat(
            ChatMessage(ChatRole.USER, "Главные тезисы"),
            ChatMessage(ChatRole.ASSISTANT, "Не получилось ответить: Модель недоступна", failed = true),
        )

        assertNull(takeableAnswer(state))
    }

    @Test
    fun `настоящий ответ забирается`() {
        val answer = "Заявка №226966 містить перелік товарів"
        val state = chat(
            ChatMessage(ChatRole.USER, "Главные тезисы"),
            ChatMessage(ChatRole.ASSISTANT, answer),
        )

        assertEquals(answer, takeableAnswer(state))
    }

    @Test
    fun `неудача после удачного ответа не подставляет предыдущий`() {
        val state = chat(
            ChatMessage(ChatRole.USER, "Главные тезисы"),
            ChatMessage(ChatRole.ASSISTANT, "Заявка №226966 містить перелік товарів"),
            ChatMessage(ChatRole.USER, "Общий вес"),
            ChatMessage(ChatRole.ASSISTANT, "Не получилось ответить: Модель недоступна", failed = true),
        )

        assertNull(takeableAnswer(state))
    }

    @Test
    fun `удавшийся повтор снова даёт что забрать`() {
        val answer = "2873,604 кг"
        val state = chat(
            ChatMessage(ChatRole.USER, "Общий вес"),
            ChatMessage(ChatRole.ASSISTANT, "Не получилось ответить: Модель недоступна", failed = true),
            ChatMessage(ChatRole.USER, "Общий вес"),
            ChatMessage(ChatRole.ASSISTANT, answer),
        )

        assertEquals(answer, takeableAnswer(state))
    }

    @Test
    fun `правило не держится на словах отказа`() {
        val state = chat(
            ChatMessage(ChatRole.USER, "Что это"),
            ChatMessage(ChatRole.ASSISTANT, "Совсем другими словами про беду", failed = true),
        )

        assertNull(takeableAnswer(state))
    }
}
