package com.point.data

import com.point.core.flow.KIND_PHONE
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Отправка наружу отдаёт текст текстом (владелец 11.08.2026: «текст принимается, отправить
 * не даёт»).
 *
 * Прежде наружу всегда уходил файл через FileProvider: адресат получал вложение «.txt»
 * вместо сообщения, а у найденного значения файла нет вовсе — там в ссылке лежит само
 * значение, и отправка обрывалась молча.
 */
class SharedTextGoesAsTextTest {

    @get:Rule val temp = TemporaryFolder()

    private fun textFile(content: String) = PointObject(
        id = "t",
        mime = "text/plain",
        uri = ScratchRef(temp.newFile().apply { writeText(content) }.path),
        state = ObjectState(ObjectKind.TEXT),
    )

    @Test
    fun `текстовый объект уходит своим текстом`() {
        // Сверяется тождество «что положили, то и уходит», а не конкретная фраза: цементировать
        // тут нечего — текст принадлежит человеку, а не Point (#584).
        val message = "Заказ 4512, оплатить до 12.08"

        assertEquals(message, shareableTextOf(textFile(message)))
    }

    @Test
    fun `найденное значение уходит собой — файла у него нет`() {
        val number = "067 636 05 60"
        val phone = PointObject(
            id = "t:phone",
            mime = "text/plain",
            uri = ValueRef(number),
            state = ObjectState(KIND_PHONE),
        )

        assertEquals(number, shareableTextOf(phone))
    }

    @Test
    fun `снимок остаётся файлом`() {
        val image = PointObject(
            id = "i",
            mime = "image/jpeg",
            uri = ScratchRef("/scratch/np-label.jpg"),
            state = ObjectState(ObjectKind.IMAGE),
        )

        assertNull(shareableTextOf(image))
    }

    @Test
    fun `длинный документ тоже остаётся файлом — в сообщение он не влезет`() {
        assertNull(shareableTextOf(textFile("а".repeat(100_001))))
    }

    @Test
    fun `пустой текст сообщением не становится`() {
        assertNull(shareableTextOf(textFile("   ")))
    }
}
