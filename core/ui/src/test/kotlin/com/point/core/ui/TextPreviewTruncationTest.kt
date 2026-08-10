package com.point.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #682/#683 — решение владельца: «Показать целиком» показывает целиком либо честно
 * называет, сколько показывает. Раньше кнопка обещала «целиком», даже когда сам
 * предпросмотр уже был обрезан пределом чтения — объект мог быть длиннее.
 */
class TextPreviewTruncationTest {

    private val nbsp = " "

    @Test
    fun `в пределах предпросмотра — обещание целиком, как раньше`() {
        assertEquals(
            "Показать целиком · ещё 500 символов",
            expandTextLabel(hiddenChars = 500, atLimit = false),
        )
    }

    @Test
    fun `предпросмотр упёрся в предел чтения — целиком больше не обещаем`() {
        assertEquals(
            "Показать больше · ещё не менее 99${nbsp}500 символов",
            expandTextLabel(hiddenChars = 99_500, atLimit = true),
        )
    }

    @Test
    fun `развёрнутый обрезанный текст честно называет, сколько показано`() {
        assertEquals(
            "Показаны первые 100${nbsp}000 символов — в объекте может быть ещё",
            truncatedPreviewNotice(shownChars = 100_000),
        )
    }
}
