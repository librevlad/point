package com.point.executors

import com.point.core.flow.TranslateCapability

import com.point.core.flow.AiReadiness
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Прочитанный снимок — это объект с текстом, а не «изображение, где текста нет» (#792,
 * решение владельца 11.08.2026).
 *
 * Живой прогон: Point прочитал снимок сам, от этого пересобрался список действий — «Распознать
 * текст» ушло, появились «Исправить ошибки» и «Найти в документе». А «Перевести» и «Открыть
 * ссылку» рядом продолжали требовать «сначала распознайте текст»: они судили по виду объекта,
 * а не по знанию о нём. Два противоречащих утверждения на одном экране.
 */
class ReadImageIsTextEnoughTest {

    private val translate = TranslateCapability(AiReadiness { true })
    private val openUrl = OpenUrlCapability()

    private val unread = ObjectState(ObjectKind.IMAGE)
    private val read = ObjectState(ObjectKind.IMAGE, features = setOf(Feature.HAS_TEXT))

    @Test
    fun `перевод берёт прочитанный снимок`() {
        assertTrue(translate.accepts(read))
        assertNull(translate.missing(read))
    }

    @Test
    fun `непрочитанный снимок переводить нечего — причина остаётся`() {
        assertFalse(translate.accepts(unread))
        assertNull(translate.missing(unread)?.takeIf { it != "сначала распознайте текст" })
    }

    @Test
    fun `у прочитанного снимка без ссылки Point не обещает, что чтение поможет`() {
        assertFalse(openUrl.accepts(read))
        assertNull(openUrl.missing(read))
    }

    @Test
    fun `у непрочитанного снимка ссылку ещё можно найти чтением`() {
        assertNull(openUrl.missing(unread)?.takeIf { it != "сначала распознайте текст" })
    }

    @Test
    fun `ссылка, найденная на снимке, открывается без повторного чтения`() {
        val withUrl = ObjectState(
            ObjectKind.IMAGE,
            features = setOf(Feature.HAS_TEXT, Feature.HAS_URL),
        )

        assertTrue(openUrl.accepts(withUrl))
        assertNull(openUrl.missing(withUrl))
    }
}
