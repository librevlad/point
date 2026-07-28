package com.point.executors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The AI free-prompt classifier (#4): a "produce «format»" request routes to a real object producer,
 * a question stays a chat answer. Pure, JVM-tested — so "сделай word" gives a .docx object, not text.
 */
class AiTransformTargetTest {

    @Test
    fun `produce-word requests route to the Word producer`() {
        assertEquals(WordPlusCapability.ID, aiTransformTarget("сделай ворд"))
        assertEquals(WordPlusCapability.ID, aiTransformTarget("В Word"))
        assertEquals(WordPlusCapability.ID, aiTransformTarget("конвертируй в docx"))
    }

    @Test
    fun `produce-excel and produce-pdf route to their producers`() {
        assertEquals(ExcelCapability.ID, aiTransformTarget("сделай таблицу в excel"))
        assertEquals(ExcelCapability.ID, aiTransformTarget("выгрузи в эксель"))
        assertEquals(PdfCapability.ID, aiTransformTarget("сделай pdf"))
        assertEquals(PdfCapability.ID, aiTransformTarget("оформи в пдф"))
    }

    @Test
    fun `questions stay a chat answer (null), even when they mention a format`() {
        assertNull(aiTransformTarget("что на изображении?"))
        assertNull(aiTransformTarget("кратко перескажи"))
        assertNull(aiTransformTarget("что такое word?"))
        assertNull(aiTransformTarget("как сделать pdf?"))
        assertNull(aiTransformTarget("объясни этот документ"))
    }

    @Test
    fun `a plain analysis prompt with no format is a chat answer`() {
        assertNull(aiTransformTarget("извлеки весь текст"))
        assertNull(aiTransformTarget("исправь ошибки и стиль"))
    }
}
