package com.point.desktop

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Живая конвертация: настоящий файл — настоящий PDF (#403).
 *
 * Тест **пропускается**, если на машине нет ни LibreOffice, ни PowerPoint: на CI их нет, и падать
 * там значило бы наказывать сборку за чужое окружение. На машине владельца он работает и ловит то,
 * чего не поймает никакой фейк, — что выбранный инструмент реально запускается и отдаёт PDF.
 */
class OfficeToPdfLiveTest {

    @Test
    fun `офисный файл превращается в PDF на этой машине`() {
        val converter = LocalOfficeToPdf()
        assumeTrue("конвертера на машине нет — пропускаем", converter.whyUnavailable() == null)

        val source = File(System.getProperty("point.test.office") ?: return)
        assumeTrue("нет исходника для проверки", source.isFile)

        val pdf = converter.convert(source)

        assertTrue("PDF не собрался", pdf != null && pdf.isFile)
        // Пустой файл — это не PDF: конвертер обязан отдать содержимое, а не оставить заготовку.
        assertTrue("PDF пустой", (pdf?.length() ?: 0) > 1000)
    }
}
