package com.point.desktop

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeToPdfLiveTest {

    @Test
    fun `офисный файл превращается в PDF на этой машине`() {
        val converter = LocalOfficeToPdf()
        assumeTrue("конвертера на машине нет — пропускаем", converter.whyUnavailable() == null)

        val source = File(System.getProperty("point.test.office") ?: return)
        assumeTrue("нет исходника для проверки", source.isFile)

        val pdf = converter.convert(source)

        assertTrue("PDF не собрался", pdf != null && pdf.isFile)

        assertTrue("PDF пустой", (pdf?.length() ?: 0) > 1000)
    }
}
