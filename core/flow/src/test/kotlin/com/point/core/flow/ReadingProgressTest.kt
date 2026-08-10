package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #682/#683 — курсор чтения для «Понять»: сколько символов объекта уже отдано в разбор,
 * и честные слова о том, что прочитан пока не весь объект.
 */
class ReadingProgressTest {

    @Test
    fun `без отметки в знании курсор — ноль`() {
        assertEquals(0, readProgressOf(emptyMap()))
    }

    @Test
    fun `отметка читается обратно числом`() {
        val metadata = mapOf(META_READ_CHARS to "24000")

        assertEquals(24_000, readProgressOf(metadata))
    }

    @Test
    fun `битую отметку курсор не роняет — считает нулём`() {
        assertEquals(0, readProgressOf(mapOf(META_READ_CHARS to "не число")))
    }

    @Test
    fun `сообщение называет оба числа разрядами и напоминает дверь`() {
        val message = partialReadMessage(read = 24_000, total = 88_452)

        assertTrue(message.contains("24 000"))
        assertTrue(message.contains("88 452"))
        assertTrue("должна напоминать про дверь «Понять» ещё раз", message.contains("«Понять»"))
    }

    @Test
    fun `оба ключа курсора — refreshable, новое значение не спорит со старым`() {
        assertTrue(META_READ_CHARS in REFRESHABLE_KNOWLEDGE)
        assertTrue(META_READ_TOTAL_CHARS in REFRESHABLE_KNOWLEDGE)
    }
}
