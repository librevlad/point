package com.point.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #645, решение владельца: «Enter отправляет» — галочка клавиатуры равна кнопке
 * «Готово», но пустой ввод — не ответ и ничего не отправляет.
 */
class AmendmentSubmitTest {

    @Test
    fun `осмысленный ответ уходит как есть`() {
        assertEquals("4411", meaningfulAmendment("4411"))
        assertEquals(" 26.04.2026 ", meaningfulAmendment(" 26.04.2026 "))
    }

    @Test
    fun `пустое и пробельное не отправляется`() {
        assertEquals(null, meaningfulAmendment(""))
        assertEquals(null, meaningfulAmendment("   "))
        assertEquals(null, meaningfulAmendment("\n"))
    }
}
