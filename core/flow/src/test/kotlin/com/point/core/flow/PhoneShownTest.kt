package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Показ номера чистит следы чтения, но не выдумывает страну (#1294).
 *
 * Номер, чью страну документ не назвал, показывается так, как он записан: группировка
 * принадлежит стране, и разобрать наугад — та же выдумка, что и назвать страну наугад
 * (решение владельца 21.08.2026 по #1029). Но скобка без пары и двойной пробел страны не
 * требуют — это следы чтения, а не запись человека.
 */
class PhoneShownTest {

    @Test
    fun `одинокая скобка и лишние пробелы уходят, цифры остаются на местах`() {
        assertEquals("06 1 2 80-44-2 1", PhoneNumbers.withoutReadingMarks("06 1 ) 2 80-44-2 1"))
    }

    @Test
    fun `ни одна цифра не пропадает и не переставляется`() {
        val raw = "06 1 ) 2 80-44-2 1"

        val shown = PhoneNumbers.withoutReadingMarks(raw)

        assertEquals(
            "цифры изменились при чистке",
            raw.filter(Char::isDigit),
            shown.filter(Char::isDigit),
        )
    }

    @Test
    fun `парные скобки — запись человека, они остаются`() {
        assertEquals("(061) 280-44-21", PhoneNumbers.withoutReadingMarks("(061) 280-44-21"))
    }

    @Test
    fun `пробел у тире — след чтения`() {
        assertEquals("280-44-21", PhoneNumbers.withoutReadingMarks("280 - 44 - 21"))
    }

    @Test
    fun `номер со страной по-прежнему приводится к виду страны`() {
        assertEquals("067 123 4567", PhoneNumbers.shown("+380671234567"))
    }
}
