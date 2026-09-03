package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Текст Word читается абзацами и строками таблицы, а слово не рвётся на границе кусков (#1420).
 *
 * Живая охота 03.09.2026, документ владельца «графік розвантаження»: Word разрезал слово «графік»
 * на два куска (`<w:t>Орієнтовний г</w:t><w:t>рафік </w:t>`), а читатель ставил пробел после
 * каждого куска — «г рафік»; абзацы и ячейки таблицы склеивались в одну строку.
 */
class OoxmlDocxTextTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `куски одного абзаца склеиваются как есть, абзацы — со своей строки`() = runTest {
        val document = docx(
            """<w:p><w:r><w:t>Орієнтовний г</w:t></w:r><w:r><w:t xml:space="preserve">рафік </w:t></w:r><w:r><w:t>виділення</w:t></w:r></w:p>""" +
                """<w:p><w:r><w:t>на 2025 рік</w:t></w:r></w:p>""",
        )

        val text = OoxmlOfficeTextExtractor().extractText(document)

        // Ожидаемое — текст документа, а не слова экрана: сторож формулировок его не считает.
        val expected = "Орієнтовний графік виділення\nна 2025 рік"
        assertEquals(expected, text)
    }

    @Test
    fun `строка таблицы — строка текста, ячейки через табуляцию`() = runTest {
        val document = docx(
            """<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Дата</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Підрозділ</w:t></w:r></w:p></w:tc></w:tr>""" +
                """<w:tr><w:tc><w:p><w:r><w:t>06.11.2025</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Зенітна батарея</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""",
        )

        val text = OoxmlOfficeTextExtractor().extractText(document)

        val expected = "Дата\tПідрозділ\n06.11.2025\tЗенітна батарея"
        assertEquals(expected, text)
    }

    @Test
    fun `перенос строки и табуляция внутри абзаца остаются`() = runTest {
        val document = docx(
            """<w:p><w:pPr><w:tabs><w:tab w:val="left" w:pos="4536"/></w:tabs></w:pPr>""" +
                """<w:r><w:t>Захід</w:t><w:tab/><w:t>2</w:t><w:br/><w:t>Примітка</w:t></w:r></w:p>""",
        )

        val expected = "Захід\t2\nПримітка"
        assertEquals("позиция табуляции из свойств абзаца — не знак табуляции", expected, OoxmlOfficeTextExtractor().extractText(document))
    }

    @Test
    fun `пустые абзацы не множат пустые строки`() = runTest {
        val document = docx("""<w:p/><w:p><w:r><w:t>Один</w:t></w:r></w:p><w:p/><w:p/><w:p><w:r><w:t>Два</w:t></w:r></w:p>""")

        val text = OoxmlOfficeTextExtractor().extractText(document)

        val expected = "Один\nДва"
        assertEquals(expected, text)
        assertFalse(text.contains("\n\n"))
    }

    private fun docx(body: String): PointObject {
        val file = File(tmp.newFolder(), "doc.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }
        return PointObject(
            "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ScratchRef(file.absolutePath),
            ObjectState(ObjectKind.OFFICE),
            metadata = mapOf("name" to "doc.docx"),
        )
    }
}
