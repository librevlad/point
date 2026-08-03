package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выбор конвертера и его отказ — чистые решения, поэтому судятся без установленного Office (#403).
 *
 * Важное здесь — отказ: если конвертировать нечем, действие обязано объявиться недоступным, а не
 * стать на телефоне кнопкой, которая ничего не сделает (#316).
 */
class OfficeToPdfTest {

    @Test
    fun `есть LibreOffice — берём её, она не требует окон`() {
        assertEquals(OfficeTool.LIBREOFFICE, chooseTool(hasLibreOffice = true, hasPowerPoint = true))
    }

    @Test
    fun `LibreOffice нет — берём PowerPoint`() {
        assertEquals(OfficeTool.POWERPOINT, chooseTool(hasLibreOffice = false, hasPowerPoint = true))
    }

    @Test
    fun `конвертировать нечем — инструмента нет, и это не падение`() {
        assertNull(chooseTool(hasLibreOffice = false, hasPowerPoint = false))
    }

    @Test
    fun `без конвертера действие объявляется недоступным словами`() {
        val why = LocalOfficeToPdf(libreOffice = null, powerPointInstalled = false).whyUnavailable()
        assertEquals("На компьютере нет LibreOffice или PowerPoint", why)
    }

    @Test
    fun `с конвертером причины недоступности нет`() {
        assertNull(LocalOfficeToPdf(libreOffice = null, powerPointInstalled = true).whyUnavailable())
    }

    @Test
    fun `офисные расширения берём, чужие — нет`() {
        assertTrue(convertible("отчёт.pptx"))
        assertTrue(convertible("договор.DOCX"))
        assertTrue(convertible("смета.xls"))
        assertTrue(convertible("презентация.odp"))
        assertFalse(convertible("фото.jpg"))
        assertFalse(convertible("архив.zip"))
        assertFalse(convertible("без-расширения"))
    }
}
