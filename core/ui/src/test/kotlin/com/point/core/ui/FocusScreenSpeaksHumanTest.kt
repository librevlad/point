package com.point.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имя механизма на экран не выходит (#1001).
 *
 * Весь экран выделения был по-русски — инструменты, кнопка очистки, подсказка внизу, — а в
 * заголовке стояло английское `Focus`: термин ADR-0001, а не слово продукта.
 */
class FocusScreenSpeaksHumanTest {

    private fun latin(text: String) = text.any { it in 'a'..'z' || it in 'A'..'Z' }

    @Test
    fun `заголовок экрана выделения говорит словами человека`() {
        assertTrue("заголовка нет вовсе", FOCUS_TITLE.isNotBlank())
        assertFalse("на экран вышло имя механизма: $FOCUS_TITLE", latin(FOCUS_TITLE))
    }

    @Test
    fun `подписи инструментов и подсказка — тоже`() {
        FOCUS_TOOL_LABELS.values.forEach {
            assertFalse("подпись инструмента не по-человечески: $it", latin(it))
        }
        assertFalse("подсказка не по-человечески: $FOCUS_HINT", latin(FOCUS_HINT))
    }
}
