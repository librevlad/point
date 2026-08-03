package com.point.print

import com.point.source.Produced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что рождается из задания печати. Чистые функции: сама печать — системная работа, а решение
 * «объект это или ничего» судится на JVM.
 */
class PrintedJobTest {

    @Test
    fun `напечатанное становится объектом-документом`() {
        val produced = printedToProduced("/cache/print/job.pdf", sizeBytes = 12_000)
        assertEquals(Produced("/cache/print/job.pdf", "application/pdf"), produced)
    }

    @Test
    fun `пустое задание объектом не становится`() {
        // Задание может закрыться, не отдав ни байта: пустой PDF в работе хуже честной тишины.
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
