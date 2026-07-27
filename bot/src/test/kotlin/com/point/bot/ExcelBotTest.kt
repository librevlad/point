package com.point.bot

import com.point.core.flow.LlmClient
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExcelBotTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `parses a json array-of-arrays, tolerating fences and junk`() {
        val rows = parseTable("```json\n[[\"Имя\",\"Сумма\"],[\"Приказ\",\"42\"]]\n```")
        assertEquals(listOf(listOf("Имя", "Сумма"), listOf("Приказ", "42")), rows)
        assertTrue(parseTable("не json").isEmpty())
    }

    @Test
    fun `writes a real xlsx with the cell values`() {
        val out = File(tmp.root, "t.xlsx")
        writeXlsx(listOf(listOf("A", "Б"), listOf("1", "2")), out)
        val sheet = ZipFile(out).use { z -> z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).readBytes().decodeToString() }
        assertTrue(sheet.contains(">Б</t>"))
        assertTrue(sheet.contains(">1</t>"))
    }

    @Test
    fun `the excel action runs the LLM and materialises an OFFICE xlsx`() = runTest {
        val src = File(tmp.root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val ans = File(tmp.root, "a.md").apply { writeText("[[\"К\",\"В\"],[\"хлеб\",\"30\"]]") }
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                assertTrue(prompt.contains("JSON"))
                return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(ans.absolutePath))
            }
        }
        val obj = PointObject("id", "image/jpeg", ScratchRef(src.absolutePath), ObjectState(ObjectKind.IMAGE))

        val result = ExcelBotRealizer(llm, tmp.newFolder("s")).perform(obj, null)

        assertTrue(result is ActionResult.Success)
        assertEquals(ObjectKind.OFFICE, (result as ActionResult.Success).result.type)
        assertTrue(File(result.result.uri.value).name.endsWith(".xlsx"))
    }

    @Test
    fun `excel accepts image pdf and text`() {
        val cap = ExcelBotCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.PDF)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertEquals(false, cap.accepts(ObjectState(ObjectKind.ZIP)))
    }
}
