package com.point.executors

import com.point.core.flow.DocBlock
import com.point.core.flow.DocStyle
import com.point.core.flow.DocxWriter
import com.point.core.flow.LlmClient
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** «В Word+» (#128): the AI twin of «В Word» — the LLM lays the raw text out as a
 *  structured document (title/headings/bullets) via a STRICT line contract. */
class WordPlusTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `parses the strict block contract and ignores garbage`() {
        val answer = """
            T=Отчёт о поездке
            P=Общие впечатления положительные.
            H=Расходы
            B=Отель — 3200
            B=Такси — 540
            мусор без префикса
            X=неизвестный ключ
        """.trimIndent()
        val blocks = parseDocBlocks(answer)
        assertEquals(
            listOf(
                DocBlock("Отчёт о поездке", DocStyle.TITLE),
                DocBlock("Общие впечатления положительные.", DocStyle.NORMAL),
                DocBlock("Расходы", DocStyle.HEADING),
                DocBlock("Отель — 3200", DocStyle.BULLET),
                DocBlock("Такси — 540", DocStyle.BULLET),
            ),
            blocks,
        )
    }

    @Test
    fun `accepts mirror the local twin and the meta is a paid network action`() {
        val cap = WordPlusCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.PDF)))
        assertEquals(false, cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertTrue(cap.meta.network)
        assertEquals("В Word+", cap.label(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `runs the LLM answer through the styled writer`() = runTest {
        val src = File(tmp.root, "raw.txt").apply { writeText("сырой текст отчёта") }
        val ans = File(tmp.root, "ans.txt").apply { writeText("T=Отчёт\nB=пункт") }
        val out = File(tmp.root, "doc.docx").apply { writeText("zip") }
        var styled: List<DocBlock>? = null
        val writer = object : DocxWriter {
            override suspend fun write(paragraphs: List<String>): ScratchRef = ScratchRef(out.absolutePath)
            override suspend fun writeStyled(blocks: List<DocBlock>): ScratchRef {
                styled = blocks
                return ScratchRef(out.absolutePath)
            }
        }
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                assertTrue(prompt.contains("сырой текст отчёта"))
                return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(ans.absolutePath))
            }
        }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))

        val result = WordPlusRealizer(llm, object : PdfTextExtractor { override suspend fun extractText(obj: PointObject) = "" }, writer).perform(obj, null)

        assertTrue(result is ActionResult.Success)
        assertEquals(DocStyle.TITLE, styled!![0].style)
        assertEquals("Отчёт", styled!![0].text)
    }
}
