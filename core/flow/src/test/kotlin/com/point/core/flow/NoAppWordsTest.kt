package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #675/#679 (охота 2026-08-09): «No Activity found to handle Intent { … dat=geo: … }»
 * уходило человеку в лицо. Нечем открыть — говорим словами и даём выход.
 */
class NoAppWordsTest {

    @Test
    fun `отказ называет, чего нет, и что делать`() {
        listOf("tel", "geo", "mailto", "https", "smsto").forEach { scheme ->
            val said = noAppFor(scheme)
            assertFalse("сырой Intent в словах для человека: $said", said.contains("Intent"))
            assertFalse(said.contains("Activity"))
            assertTrue("нет выхода из отказа: $said", said.contains("скопировать"))
        }
    }

    @Test
    fun `незнакомая схема тоже получает человеческие слова`() {
        val said = noAppFor("zoomus")

        assertTrue(said.contains("нечем это открыть"))
        assertTrue(said.contains("скопировать"))
    }
}
