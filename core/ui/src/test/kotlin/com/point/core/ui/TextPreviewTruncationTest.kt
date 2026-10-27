package com.point.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #682/#683 — решение владельца: «Показать целиком» показывает целиком либо честно
 * называет, сколько показывает. Раньше кнопка обещала «целиком», даже когда сам
 * предпросмотр уже был обрезан пределом чтения — объект мог быть длиннее.
 */
class TextPreviewTruncationTest {

    @Test
    fun `в пределах предпросмотра — обещание целиком, как раньше`() {
        val label = expandTextLabel(hiddenChars = 500, atLimit = false)
        assertTrue("starts with Показать целиком", label.startsWith("Показать целиком"))
        assertTrue("contains hidden count", label.contains("500"))
        assertTrue("contains suffix", label.contains("символов"))
    }

    @Test
    fun `предпросмотр упёрся в предел чтения — целиком больше не обещаем`() {
        val label = expandTextLabel(hiddenChars = 99_500, atLimit = true)
        assertTrue("starts with Показать больше", label.startsWith("Показать больше"))
        assertTrue("contains 'не менее'", label.contains("не менее"))
        // grouped uses nbsp for thousands separator
        assertTrue("contains hidden count", label.contains("99 500") || label.contains("99 500"))
        assertTrue("contains suffix", label.contains("символов"))
    }

    /**
     * Текст короче предела предпросмотра, но длиннее трёх строк на экране: скрытых символов
     * не считано, а раскрывать есть что (#871). Число тут было бы враньём про то, что человек
     * видит своими глазами.
     */
    @Test
    fun `обрезано экраном, а не пределом — кнопка зовёт без числа`() {
        listOf(0 to false, 0 to true, -1 to true).forEach { (hidden, atLimit) ->
            val label = expandTextLabel(hiddenChars = hidden, atLimit = atLimit)

            assertFalse("число в надписи: $label", label.any { it.isDigit() })
            assertTrue("кнопка зовёт раскрыть: $label", label.contains("Показать"))
        }
    }

    @Test
    fun `развёрнутый обрезанный текст честно называет, сколько показано`() {
        val label = truncatedPreviewNotice(shownChars = 100_000)
        assertTrue("starts with Показаны первые", label.startsWith("Показаны первые"))
        assertTrue("contains shown count", label.contains("100 000") || label.contains("100 000"))
        assertTrue("contains suffix", label.contains("в объекте может быть ещё"))
    }
}
