package com.point.print

import com.point.source.Produced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintedJobTest {

    @Test
    fun `напечатанное становится объектом-документом`() {
        val produced = printedToProduced("/cache/print/job.pdf", sizeBytes = 12_000)
        assertEquals(Produced("/cache/print/job.pdf", "application/pdf"), produced)
    }

    @Test
    fun `пустое задание объектом не становится`() {

        assertNull(printedToProduced("/cache/print/job.pdf", sizeBytes = 0))
    }

    @Test
    fun `имя берётся от задания, чтобы человек узнал свой документ`() {
        assertEquals("Счёт за май.pdf", printedFileName("Счёт за май"))
    }

    @Test
    fun `имя без названия — общее, но не пустое`() {
        assertEquals("Печать.pdf", printedFileName(null))
        assertEquals("Печать.pdf", printedFileName("   "))
    }

    @Test
    fun `в имени нет разделителей пути — иначе файл уедет из своей папки`() {
        val name = printedFileName("отчёт/май\\v2")
        assertTrue(name, !name.contains('/') && !name.contains('\\'))
    }
}
