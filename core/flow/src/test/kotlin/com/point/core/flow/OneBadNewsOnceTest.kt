package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Плохую новость про объект человеку говорят один раз (#874).
 *
 * Пустой файл: причина «Файл пустой — в нём нечего читать» была верной, но написана на экране
 * шесть раз подряд — подписью объекта и второй строкой у каждого действия, а под прокруткой
 * ещё больше. Правило #582 («дверь не исчезает, а называет причину заранее») верно, когда
 * причина у действия своя; общую на всех говорит подпись объекта.
 */
class OneBadNewsOnceTest {

    @Test
    fun `одна причина на все действия — общая`() {
        val reasons = List(5) { EMPTY_FILE_REASON }

        assertEquals(EMPTY_FILE_REASON, sharedUnusableReason(reasons))
    }

    @Test
    fun `своя причина у одного действия общей не считается`() {
        val reasons = listOf(EMPTY_FILE_REASON, "нет интернета", EMPTY_FILE_REASON)

        assertNull(sharedUnusableReason(reasons))
    }

    @Test
    fun `у единственного действия причина остаётся при нём`() {
        assertNull("одному действию сказать больше некому", sharedUnusableReason(listOf(EMPTY_FILE_REASON)))
    }

    @Test
    fun `там, где причин нет, ничего не выдумывается`() {
        assertNull(sharedUnusableReason(listOf(null, null, null)))
        assertNull(sharedUnusableReason(emptyList()))
        assertNull("пустая строка — не знание", sharedUnusableReason(listOf("", "")))
    }
}
