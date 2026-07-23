package com.point.executors

import com.point.core.flow.LlmClient
import com.point.core.flow.SpreadsheetWriter
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The realizer parses the LLM's TSV into rows and hands them to the writer. */
class ExcelRealizerTest {

    // The real LlmClient writes its answer to scratch and returns a ResultObject to it.
    private fun llm(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            val f = File.createTempFile("point-ans", ".txt").apply { deleteOnExit(); writeText(answer) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
        }
    }

    private var lastRows: List<List<String>>? = null
    private val writer = object : SpreadsheetWriter {
        override suspend fun write(rows: List<List<String>>): ScratchRef {
            lastRows = rows
            return ScratchRef(File.createTempFile("point-xlsx", ".xlsx").apply { deleteOnExit() }.absolutePath)
        }
    }

    private val image = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `parses TSV into rows and produces an OFFICE xlsx`() = runTest {
        val result = ExcelRealizer(llm("Имя\tСумма\nПриказ\t42"), writer).perform(image)
        assertTrue(result is ActionResult.Success)
        assertEquals(ObjectKind.OFFICE, (result as ActionResult.Success).result.type)
        assertEquals(listOf(listOf("Имя", "Сумма"), listOf("Приказ", "42")), lastRows)
    }

    @Test
    fun `tolerates a code fence the model may wrap around the TSV`() = runTest {
        val result = ExcelRealizer(llm("```tsv\nA\tB\n1\t2\n```"), writer).perform(image)
        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), lastRows)
    }

    @Test
    fun `a blank answer surfaces a recoverable failure`() = runTest {
        val result = ExcelRealizer(llm("   "), writer).perform(image)
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `parses a structured JSON table (issue 22)`() = runTest {
        val result = ExcelRealizer(llm("""[["Имя","Сумма"],["Приказ","42"]]"""), writer).perform(image)
        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("Имя", "Сумма"), listOf("Приказ", "42")), lastRows)
    }

    @Test
    fun `tolerates a json code fence`() = runTest {
        ExcelRealizer(llm("```json\n[[\"A\",\"B\"],[\"1\",\"2\"]]\n```"), writer).perform(image)
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), lastRows)
    }

    @Test
    fun `falls back to TSV when the model answers in the old delimited format`() {
        // parseTable prefers JSON but keeps working on plain TSV.
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), parseTable("A\tB\n1\t2"))
        assertEquals(emptyList<List<String>>(), parseTable("   "))
    }
}
