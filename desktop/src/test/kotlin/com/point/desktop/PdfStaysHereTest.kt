package com.point.desktop

import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Аудит 2026-08-09, блок 2.4: PDF уезжал только в очередь телефона — человек собрал
 * его на компьютере и не мог на компьютере открыть. Результат — самостоятельный
 * объект и появляется здесь (PC3/P4); телефону при его команде тот же Success
 * уедет ответом, а поздний — очередью компьютера.
 */
class PdfStaysHereTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `собранный PDF — объект здесь, а не только письмо телефону`() = runTest {
        val pdf = temp.newFile("смета.pdf").apply { writeBytes("%PDF-1.4".toByteArray()) }
        val converter = object : OfficeToPdf {
            override fun whyUnavailable(): String? = null
            override fun convert(source: File): File = pdf
        }
        val source = temp.newFile("смета.docx").apply { writeBytes(ByteArray(16)) }

        val result = PcOfficePdfRealizer(converter).perform(
            PointObject(
                "doc", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ScratchRef(source.absolutePath), ObjectState(ObjectKind.OFFICE),
                metadata = mapOf("name" to "смета.docx"),
            ),
            null,
        )

        val born = (result as ActionResult.Success).result
        assertEquals(ObjectKind.PDF, born.type)
        assertEquals("application/pdf", born.mime)
        assertEquals("смета.pdf", born.metadata["name"])
        assertTrue(File(born.uri.value).isFile)
    }
}
