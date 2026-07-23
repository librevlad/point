package com.point.executors

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one-tap default target must always flip the language, so a translate visibly changes text. */
class TranslateActionTest {

    @Test
    fun `russian text defaults to english`() {
        assertEquals("английский", translateDefaultTarget("Привет, как дела? Это тест."))
    }

    @Test
    fun `english text defaults to russian`() {
        assertEquals("русский", translateDefaultTarget("Hello, how are you? This is a test."))
    }

    @Test
    fun `mostly-latin mixed text defaults to russian`() {
        assertEquals("русский", translateDefaultTarget("Meeting at 10: agenda, notes, action items"))
    }
}
